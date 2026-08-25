package org.sscc.ssccopsserver.domain.academicprogram.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sscc.ssccopsserver.domain.academicprogram.code.error.AcademicProgramErrorCode;
import org.sscc.ssccopsserver.domain.academicprogram.dto.CurriculumItemDraft;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/*
 * 커리큘럼 줄 파서 (#150).
 *
 * 스프링을 띄우지 않는다 — 이 클래스는 문자열만 다루고, 그 규칙이 깨졌을 때 컨텍스트 기동
 * 시간만큼 늦게 아는 것에 이득이 없다.
 *
 * 기대값을 ProposalFormSeed.CURRICULUM_LINE_FORMAT으로 조립하지 않고 **리터럴로 적는다.**
 * 상수를 고치면 파서와 테스트가 함께 따라 움직여 조용히 통과하는데, 그 상수는 이미 접수된
 * 기획안의 제출자가 읽은 안내라 바뀌면 안 되는 값이다(ProposalFormSeedTest와 같은 태도).
 */
class ProposalCurriculumParserTest {

    private final ProposalCurriculumParser parser = new ProposalCurriculumParser();

    @Test
    void parsesLinesInTheAnnouncedFormat() {
        List<CurriculumItemDraft> items =
                parser.parse(
                        """
                        1회차 | 오리엔테이션 | 2026-03-05
                        2회차 | React, 그리고 상태관리 | 2026-03-12
                        """);

        assertThat(items)
                .extracting(
                        CurriculumItemDraft::seqno,
                        CurriculumItemDraft::ttl,
                        CurriculumItemDraft::planDt)
                .containsExactly(
                        tuple(1, "오리엔테이션", LocalDate.of(2026, 3, 5)),
                        tuple(2, "React, 그리고 상태관리", LocalDate.of(2026, 3, 12)));
    }

    // 날짜는 생략할 수 있다 — 안내 문구가 그렇게 적혀 있고 plan_dt도 NULL 허용이다
    @Test
    void acceptsLinesWithoutDate() {
        List<CurriculumItemDraft> items = parser.parse("1회차 | 오리엔테이션\n2회차 | 훅 |");

        assertThat(items).extracting(CurriculumItemDraft::planDt).containsExactly(null, null);
    }

    // 빈 줄은 형식 위반이 아니다. 회차 사이에 줄바꿈을 하나 더 넣는 것은 흔한 일이다
    @Test
    void skipsBlankLines() {
        assertThat(parser.parse("\n1회차 | 오리엔테이션\n\n2회차 | 훅\n\n")).hasSize(2);
    }

    // "1"도 "1회차"도 같은 뜻이다 — 뜻이 하나로 읽히는 흔들림은 받아 준다
    @Test
    void acceptsSequenceWithoutSuffix() {
        assertThat(parser.parse("1 | 오리엔테이션"))
                .extracting(CurriculumItemDraft::seqno)
                .containsExactly(1);
    }

    /*
     * 구분자가 모자라면 무엇이 주제인지 정할 수 없다. 줄 전체를 주제로 삼고 회차를 순서로
     * 매기는 쪽은 택하지 않았다 — 추측한 값이 승인과 함께 확정되고 이후 고칠 수 없다.
     */
    @Test
    void rejectsLineWithoutDelimiter() {
        assertThatFails(() -> parser.parse("1회차 오리엔테이션"), "커리큘럼 1번째 줄");
    }

    @Test
    void rejectsLineWithNonNumericSequence() {
        assertThatFails(() -> parser.parse("1회차 | 오리엔테이션\n둘째 | 훅"), "회차 번호를 숫자로 읽을 수 없습니다");
    }

    @Test
    void rejectsLineWithBlankTitle() {
        assertThatFails(() -> parser.parse("1회차 |  | 2026-03-05"), "주제가 비어 있습니다");
    }

    // 날짜 형식은 ISO 하나다. 03/05가 3월 5일인지 5월 3일인지를 서버가 정하지 않는다
    @Test
    void rejectsLineWithUnreadableDate() {
        assertThatFails(() -> parser.parse("1회차 | 오리엔테이션 | 2026/03/05"), "날짜를 읽을 수 없습니다");
    }

    // 같은 회차가 둘이면 회차 기록(#135)이 어느 줄을 가리키는지 정할 수 없다
    @Test
    void rejectsDuplicatedSequence() {
        assertThatFails(() -> parser.parse("1회차 | 오리엔테이션\n1회차 | 훅"), "중복됩니다");
    }

    @Test
    void rejectsEmptyText() {
        assertThatFails(() -> parser.parse("   \n  "), "커리큘럼이 비어 있습니다");
    }

    /*
     * 사유에 몇 번째 줄인지가 들어 있어야 검토자가 수정요청에 옮겨 적을 수 있다.
     * 첫 인자의 메시지가 아니라 GeneralException의 detail을 보는 것은 그 값이 그대로 400 응답의
     * message가 되기 때문이다.
     */
    private static void assertThatFails(Runnable parsing, String expectedReasonFragment) {
        assertThatThrownBy(parsing::run)
                .isInstanceOf(GeneralException.class)
                .satisfies(
                        thrown -> {
                            GeneralException ex = (GeneralException) thrown;
                            assertThat(ex.getErrorCode())
                                    .isEqualTo(AcademicProgramErrorCode.PROPOSAL_MIGRATION_FAILED);
                            assertThat(ex.getDetail()).contains(expectedReasonFragment);
                        });
    }
}
