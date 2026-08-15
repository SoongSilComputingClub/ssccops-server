package org.sscc.ssccopsserver.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.sscc.ssccopsserver.domain.member.code.error.MemberErrorCode;
import org.sscc.ssccopsserver.domain.member.dto.MemberImportCsv;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/*
 * 이관 CSV 파서 (#84).
 *
 * 확인의 중심은 **"직접 split 했다면 깨졌을 파일이 그대로 읽힌다"**이다 — 따옴표 안의 쉼표,
 * 줄바꿈을 포함한 필드, 이스케이프된 따옴표, 그리고 엑셀이 붙이는 BOM. 파서가 서버 한 곳뿐이라
 * 여기서 갈리면 화면에서 매핑한 컬럼과 서버가 읽는 컬럼이 통째로 어긋난다.
 *
 * rowNo가 레코드 순번이 아니라 줄 번호라는 것도 여기서 못 박는다. 운영자는 응답의 숫자를 들고
 * 파일을 열어 그 줄을 찾는다.
 */
class MemberImportParserTest {

    private final MemberImportParser parser = new MemberImportParser();

    /** 헤더 순서는 파일이 정한다 — 서버가 기대하는 순서를 요구하면 명부마다 파일을 고쳐야 한다 */
    @Test
    void readsHeadersInFileOrder() {
        MemberImportCsv csv = parse("학번,이름,등급\n20211234,홍길동,정회원\n");

        assertThat(csv.headers()).containsExactly("학번", "이름", "등급");
        assertThat(csv.rows()).hasSize(1);
        assertThat(csv.rows().get(0).values()).containsExactly("20211234", "홍길동", "정회원");
    }

    /*
     * 따옴표로 감싼 값 안의 쉼표는 구분자가 아니다. 직접 split 하면 여기서 반드시 깨진다 —
     * 역할 컬럼이 있는 실제 명부('회장,프로젝트장')가 이 모양이다.
     */
    @Test
    void keepsCommasInsideQuotedValues() {
        MemberImportCsv csv = parse("이름,\"역할\"\n홍길동,\"회장,프로젝트장\"\n");

        assertThat(csv.headers()).containsExactly("이름", "역할");
        assertThat(csv.rows().get(0).values()).containsExactly("홍길동", "회장,프로젝트장");
    }

    /** 따옴표 안의 두 겹 따옴표는 값에 든 따옴표 한 개다 */
    @Test
    void unescapesDoubledQuotes() {
        MemberImportCsv csv = parse("이름,비고\n홍길동,\"별칭은 \"\"길동\"\"이다\"\n");

        assertThat(csv.rows().get(0).values().get(1)).isEqualTo("별칭은 \"길동\"이다");
    }

    /*
     * BOM이 붙은 UTF-8. 엑셀이 붙여 내보내므로 떼지 않으면 첫 헤더가 '﻿이름'이 되어
     * 매핑에서 이름 컬럼만 통째로 빠진다 — 터지지 않고 조용히 틀리는 종류다.
     */
    @Test
    void stripsUtf8ByteOrderMark() {
        MemberImportCsv csv = parse("﻿이름,학번\n홍길동,20211234\n");

        assertThat(csv.headers()).containsExactly("이름", "학번");
    }

    /*
     * 줄바꿈을 포함한 필드가 있으면 레코드 순번과 줄 번호가 갈린다. 여기서는 2번째 데이터
     * 레코드가 5행에서 시작한다 — rowNo가 3(레코드 순번 + 헤더)이면 운영자는 엉뚱한 줄을 고친다.
     */
    @Test
    void rowNumberFollowsPhysicalLinesEvenWithEmbeddedNewlines() {
        MemberImportCsv csv = parse("이름,비고\n" + "홍길동,\"첫째 줄\n둘째 줄\n셋째 줄\"\n" + "김철수,비고 없음\n");

        assertThat(csv.rows()).hasSize(2);
        assertThat(csv.rows().get(0).rowNo()).isEqualTo(2);
        assertThat(csv.rows().get(0).values().get(1)).isEqualTo("첫째 줄\n둘째 줄\n셋째 줄");
        assertThat(csv.rows().get(1).rowNo()).isEqualTo(5);
    }

    /** 헤더가 1행이므로 첫 데이터 행은 2행이다. 응답 DTO가 못 박은 기준이다 */
    @Test
    void firstDataRowIsLineTwo() {
        MemberImportCsv csv = parse("이름\n홍길동\n김철수\n");

        assertThat(csv.rows().get(0).rowNo()).isEqualTo(2);
        assertThat(csv.rows().get(1).rowNo()).isEqualTo(3);
    }

    /** 값 양끝의 공백은 뗀다. "홍길동 , 20211234"처럼 구분자 뒤에 공백을 둔 명부가 흔하다 */
    @Test
    void trimsSurroundingWhitespace() {
        MemberImportCsv csv = parse("이름 , 학번 \n 홍길동 , 20211234 \n");

        assertThat(csv.headers()).containsExactly("이름", "학번");
        assertThat(csv.rows().get(0).values()).containsExactly("홍길동", "20211234");
    }

    /*
     * 헤더보다 짧은 행도 읽는다. 엑셀은 꼬리의 빈 칸을 생략해 내보내는 일이 있는데, 길이 불일치를
     * 오류로 끊으면 그 흔한 파일이 통째로 거절된다 — 없는 컬럼은 빈 값으로 본다.
     */
    @Test
    void readsRowsShorterThanHeader() {
        MemberImportCsv csv = parse("이름,학번,연락처\n홍길동,20211234\n");

        assertThat(csv.rows().get(0).valueAt(2)).isEmpty();
    }

    /** 값이 하나도 없는 줄은 데이터가 아니다 — 세면 마지막 한 행이 늘 "회원명 누락"으로 잡힌다 */
    @Test
    void skipsBlankRows() {
        MemberImportCsv csv = parse("이름,학번\n홍길동,20211234\n,\n");

        assertThat(csv.rows()).hasSize(1);
    }

    @Test
    void rejectsFileWithoutAnyRow() {
        assertThatThrownBy(() -> parse(""))
                .isInstanceOf(GeneralException.class)
                .extracting("errorCode")
                .isEqualTo(MemberErrorCode.EMPTY_CSV_FILE);
    }

    /** 헤더만 있고 데이터가 없는 파일도 빈 파일이다 — 0건 검증을 성공으로 돌려주면 위저드가 넘어간다 */
    @Test
    void rejectsHeaderOnlyFile() {
        assertThatThrownBy(() -> parse("이름,학번\n"))
                .isInstanceOf(GeneralException.class)
                .extracting("errorCode")
                .isEqualTo(MemberErrorCode.EMPTY_CSV_FILE);
    }

    /*
     * UTF-8로 읽을 수 없는 바이트는 물음표로 흘려보내지 않고 거절한다. 통과시키면 '?????'라는
     * 이름이 이관되고, 그 회원은 나중에 어느 줄에서 왔는지도 알 수 없다.
     */
    @Test
    void rejectsNonUtf8Content() {
        byte[] cp949 = {
            (byte) 0xC8, (byte) 0xAB, (byte) 0xB1, (byte) 0xE6, (byte) 0xB5, (byte) 0xBF
        };

        assertThatThrownBy(() -> parser.parse(cp949))
                .isInstanceOf(GeneralException.class)
                .extracting("errorCode")
                .isEqualTo(MemberErrorCode.INVALID_CSV_FILE);
    }

    private MemberImportCsv parse(String content) {
        return parser.parse(content.getBytes(StandardCharsets.UTF_8));
    }
}
