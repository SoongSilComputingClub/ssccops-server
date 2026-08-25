package org.sscc.ssccopsserver.domain.academicprogram.service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.academicprogram.code.error.AcademicProgramErrorCode;
import org.sscc.ssccopsserver.domain.academicprogram.dto.CurriculumItemDraft;
import org.sscc.ssccopsserver.domain.form.service.ProposalFormSeed;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/*
 * 기획안의 커리큘럼 자유 텍스트 → 회차 목록 (#150).
 *
 * ── 명세는 제출자가 읽은 문장이다 ──────────────────────────────
 * 이 파서가 따르는 형식은 ProposalFormSeed.CURRICULUM_LINE_FORMAT("1회차 | 주제 | 2026-03-05")
 * 하나이며, 그 상수는 문항 문구(qitemLblNm)에 그대로 들어가 제출자에게 보인다. 여기에 형식을
 * 다시 적지 않고 상수를 가리키는 것은 #173이 세운 규칙 그대로다 — 두 곳에 따로 적으면 반드시
 * 갈리고, 갈린 순간 제출자는 안내대로 적었는데 승인이 막힌다. 오류 문장도 그 상수를 그대로
 * 인용해 "무엇처럼 적어야 하는가"가 안내와 한 글자도 다르지 않게 한다.
 *
 * ── 관대함의 경계 ─────────────────────────────────────────────
 * 공백·빈 줄·회차 번호의 '회차' 접미사처럼 **뜻이 하나로 읽히는 흔들림**은 받아 준다. 반대로
 * 회차 번호가 없거나 주제가 비었거나 날짜가 날짜가 아닌 것처럼 **무엇을 뜻하는지 정할 수 없는
 * 줄**은 추측하지 않고 거절한다 — 추측한 값은 승인과 함께 그대로 확정되고(curriculum_item은
 * 불변이다) 나중에 고칠 방법이 없다.
 *
 * 회차 번호를 줄 순서로 대신 매기는 쪽(1번째 줄 = 1회차)도 후보였지만 쓰지 않았다. 제출자가
 * 3회차를 빼먹었거나 순서를 바꿔 적었을 때 그것이 그대로 묻히고, 검토자는 자기가 읽은 번호와
 * 다른 번호로 저장된 것을 알 수 없다. 번호는 제출자가 적은 값이지 서버가 부여하는 값이 아니다.
 *
 * ── 중복·역순 회차는 막는다 ───────────────────────────────────
 * 같은 회차 번호가 두 줄이면 진행률·회차 기록(#134·#135)이 어느 줄을 가리키는지 정할 수 없다.
 * 오름차순이 아닌 것은 허용한다 — 순서가 뒤섞여도 seqno로 정렬해 읽으므로 뜻이 흔들리지 않는다.
 */
@Component
public class ProposalCurriculumParser {

    /** 구분자. 주제에 쉼표·하이픈·콜론이 흔히 들어가서 '|'를 골랐다 (#173) */
    private static final String FIELD_DELIMITER = "\\|";

    /** 회차 번호에 붙는 접미사. "1"도 "1회차"도 같은 뜻으로 읽는다 */
    private static final String SEQUENCE_SUFFIX = "회차";

    private static final int FIELD_COUNT_WITHOUT_DATE = 2;

    private static final int FIELD_COUNT_WITH_DATE = 3;

    /** curriculum_item.ttl의 길이 상한. DB가 자르기 전에 사유를 붙여 거절한다 */
    private static final int MAX_TITLE_LENGTH = 200;

    /*
     * 파싱. 실패는 전부 PROPOSAL_MIGRATION_FAILED이며 사유에 **몇 번째 줄인지**를 함께 담는다 —
     * 검토자가 수정요청에 옮겨 적을 문장이라 "형식이 잘못됐습니다"만으로는 쓸모가 없다.
     */
    public List<CurriculumItemDraft> parse(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            throw failure("커리큘럼이 비어 있습니다.");
        }

        List<CurriculumItemDraft> items = new ArrayList<>();
        List<Integer> seenSequences = new ArrayList<>();
        String[] lines = rawText.split("\\R");

        for (int index = 0; index < lines.length; index++) {
            String line = lines[index].trim();
            // 빈 줄은 건너뛴다 — 회차 사이에 줄바꿈을 하나 더 넣는 것은 형식 위반이 아니다
            if (line.isEmpty()) {
                continue;
            }
            CurriculumItemDraft item = parseLine(line, index + 1);
            if (seenSequences.contains(item.seqno())) {
                throw failure(lineNumber(index + 1) + " 회차 번호 " + item.seqno() + "이(가) 중복됩니다.");
            }
            seenSequences.add(item.seqno());
            items.add(item);
        }

        if (items.isEmpty()) {
            throw failure("커리큘럼이 비어 있습니다.");
        }
        return List.copyOf(items);
    }

    private CurriculumItemDraft parseLine(String line, int lineNumber) {
        String[] fields = line.split(FIELD_DELIMITER, -1);
        if (fields.length != FIELD_COUNT_WITHOUT_DATE && fields.length != FIELD_COUNT_WITH_DATE) {
            throw failure(
                    lineNumber(lineNumber)
                            + " \""
                            + ProposalFormSeed.CURRICULUM_LINE_FORMAT
                            + "\" 형식이 아닙니다. (날짜는 생략할 수 있습니다)");
        }

        int seqno = parseSequence(fields[0].trim(), lineNumber);
        String title = fields[1].trim();
        if (title.isEmpty()) {
            throw failure(lineNumber(lineNumber) + " 주제가 비어 있습니다.");
        }
        if (title.length() > MAX_TITLE_LENGTH) {
            throw failure(lineNumber(lineNumber) + " 주제가 " + MAX_TITLE_LENGTH + "자를 넘습니다.");
        }

        LocalDate planDate =
                fields.length == FIELD_COUNT_WITH_DATE
                        ? parsePlanDate(fields[2].trim(), lineNumber)
                        : null;

        return new CurriculumItemDraft(seqno, title, planDate);
    }

    /*
     * "1"·"1회차" 둘 다 1로 읽는다. 접미사를 요구하지도, 금지하지도 않는 것은 안내 문구에 그것이
     * 붙어 있어 대부분 따라 적지만 숫자만 적는 것도 뜻이 하나이기 때문이다.
     */
    private int parseSequence(String rawSequence, int lineNumber) {
        String digits =
                rawSequence.endsWith(SEQUENCE_SUFFIX)
                        ? rawSequence
                                .substring(0, rawSequence.length() - SEQUENCE_SUFFIX.length())
                                .trim()
                        : rawSequence;
        try {
            int seqno = Integer.parseInt(digits);
            if (seqno <= 0) {
                throw failure(lineNumber(lineNumber) + " 회차 번호는 1 이상이어야 합니다.");
            }
            return seqno;
        } catch (NumberFormatException ex) {
            throw failure(
                    lineNumber(lineNumber) + " 회차 번호를 숫자로 읽을 수 없습니다: \"" + rawSequence + "\"");
        }
    }

    /*
     * 날짜는 ISO(2026-03-05)만 받는다. 안내 문구의 예시가 그 형식이고, 여러 형식을 받아 주면
     * "03/05"가 3월 5일인지 5월 3일인지를 서버가 정하게 된다 — 확정되면 되돌릴 수 없는 값이다.
     *
     * 빈 문자열은 "날짜를 생략했다"로 읽는다("1회차 | 주제 |"처럼 구분자만 남긴 경우다).
     */
    private LocalDate parsePlanDate(String rawDate, int lineNumber) {
        if (rawDate.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(rawDate);
        } catch (DateTimeParseException ex) {
            throw failure(
                    lineNumber(lineNumber) + " 날짜를 읽을 수 없습니다: \"" + rawDate + "\" (예: 2026-03-05)");
        }
    }

    private static String lineNumber(int lineNumber) {
        return "커리큘럼 " + lineNumber + "번째 줄:";
    }

    private static GeneralException failure(String reason) {
        return new GeneralException(AcademicProgramErrorCode.PROPOSAL_MIGRATION_FAILED, reason);
    }
}
