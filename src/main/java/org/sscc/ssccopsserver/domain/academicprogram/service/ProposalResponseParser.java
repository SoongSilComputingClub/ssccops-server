package org.sscc.ssccopsserver.domain.academicprogram.service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.academicprogram.code.error.AcademicProgramErrorCode;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramPreviewResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.ProposalDraft;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramTypeEntity;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramTypeRepository;
import org.sscc.ssccopsserver.domain.form.entity.ResponseContent;
import org.sscc.ssccopsserver.domain.form.service.ProposalFormSeed;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

/*
 * 기획안 폼 응답(rspns_cn) → ProposalDraft (#150). 이슈 체크리스트의 "ProposalFormMapping"이
 * 이 클래스다 — 매핑 상수만 늘어놓는 대신 그 매핑으로 실제 값을 읽는 것까지 한 자리에 뒀다.
 * 상수 표와 그것을 읽는 코드를 나누면 표에 있는데 아무도 읽지 않는 항목, 표에 없는데 읽는
 * 항목이 생기고, 그 어긋남은 컴파일러가 잡아 주지 못한다.
 *
 * ── qitemId는 이 파일에 적지 않는다 ───────────────────────────
 * 전부 ProposalFormSeed의 상수를 가리킨다. 문자열을 다시 적으면 시드가 넣는 key와 이관이 읽는
 * key가 두 벌이 되어, 오타 하나가 "접수는 되는데 승인은 영원히 안 되는" 폼으로 배포된다.
 * 여섯 개 필수 문항의 존재는 SystemFormContract가 폼 저장 경로에서 보장하지만
 * (SYSTEM_FORM_CONTRACT_VIOLATION), 그것은 문항이 있다는 보장이지 제출자가 채웠다는 보장은
 * 아니다 — 그래서 여기서도 비어 있으면 거절한다.
 *
 * ── 파싱은 여기 한 곳뿐이다 ───────────────────────────────────
 * 실제 이관(AcademicProgramMigrationServiceImpl)과 검토 미리보기(preview)가 같은 parse를
 * 부른다. 미리보기가 자기 파싱을 따로 하면 검토자는 화면에서 본 것과 다른 것을 승인하게 된다
 * (ssccops#148 BR).
 *
 * ── 유형 문자열 → 코드 ────────────────────────────────────────
 * 응답에는 선택지 문자열("스터디")이 저장되고 academic_program은 코드("STUDY")를 참조한다.
 * 그 다리를 코드 안의 Map으로 굳히지 않고 **academic_program_type.type_nm 조회**로 놓았다 —
 * 유형은 배포 없이 시드 추가로 늘어나야 하는 기준정보이고(#130), 상수 맵을 두면 세미나 유형을
 * 하나 추가할 때 서버 배포가 함께 필요해진다. 이름이 같은지는 ProposalFormSeedTest가
 * 이미 못 박고 있으므로(선택지 문자열 == type_nm), 조회가 빈손이라는 것은 곧 운영진이 어느
 * 한쪽만 바꿨다는 뜻이다 — 조용히 넘기지 않고 사유와 함께 거절한다.
 *
 * use_yn을 보지 않는 것은 의도된 것이다. 제출자는 접수 시점에 폼이 보여 준 선택지를 골랐고,
 * 그 뒤 운영진이 유형을 비활성으로 내렸다고 해서 이미 낸 기획안이 승인 불가가 되는 것은
 * 제출자가 어찌할 수 없는 이유로 막는 것이다(비활성은 "새로 고를 수 없다"이지 "있던 것을
 * 무효로 한다"가 아니다 — FORM_LABEL_NOT_USABLE과 같은 규칙).
 */
@Component
@RequiredArgsConstructor
public class ProposalResponseParser {

    /** event.plc_nm의 길이 상한 */
    private static final int MAX_PLACE_NAME_LENGTH = 100;

    /** academic_program.schedule_txt의 길이 상한 */
    private static final int MAX_SCHEDULE_TEXT_LENGTH = 100;

    /** event.event_ttl의 길이 상한 */
    private static final int MAX_TITLE_LENGTH = 256;

    private final AcademicProgramTypeRepository academicProgramTypeRepository;
    private final ProposalCurriculumParser proposalCurriculumParser;

    /*
     * 승인 시 실제로 만들 값. 실패는 GeneralException(PROPOSAL_MIGRATION_FAILED) + 사유이며
     * 그 예외가 검토 API의 트랜잭션을 통째로 롤백시킨다.
     */
    public ProposalDraft parse(ResponseContent content) {
        Map<String, Object> answers = content == null ? Map.of() : content.answers();

        LocalDate beginDate =
                requiredDate(answers, ProposalFormSeed.QITEM_PERIOD_BEGIN_DATE, "활동 기간 시작");
        LocalDate endDate =
                requiredDate(answers, ProposalFormSeed.QITEM_PERIOD_END_DATE, "활동 기간 종료");
        if (endDate.isBefore(beginDate)) {
            throw failure("활동 기간 종료가 시작보다 빠릅니다.");
        }

        Integer capacityMin =
                optionalCount(answers, ProposalFormSeed.QITEM_CAPACITY_MIN_COUNT, "모집 정원 하한");
        Integer capacityMax =
                optionalCount(answers, ProposalFormSeed.QITEM_CAPACITY_MAX_COUNT, "모집 정원 상한");
        if (capacityMin != null && capacityMax != null && capacityMax < capacityMin) {
            throw failure("모집 정원 상한이 하한보다 작습니다.");
        }

        return new ProposalDraft(
                resolveType(requiredText(answers, ProposalFormSeed.QITEM_PROGRAM_TYPE, "유형")),
                limited(
                        requiredText(answers, ProposalFormSeed.QITEM_PROGRAM_TITLE, "활동명"),
                        MAX_TITLE_LENGTH,
                        "활동명"),
                requiredText(answers, ProposalFormSeed.QITEM_GOAL_CONTENT, "활동 소개·목표"),
                optionalText(answers, ProposalFormSeed.QITEM_PREP_CONTENT),
                beginDate,
                endDate,
                limited(
                        optionalText(answers, ProposalFormSeed.QITEM_SCHEDULE_TEXT),
                        MAX_SCHEDULE_TEXT_LENGTH,
                        "정기 일정"),
                capacityMin,
                capacityMax,
                limited(
                        optionalText(answers, ProposalFormSeed.QITEM_PLACE_NAME),
                        MAX_PLACE_NAME_LENGTH,
                        "희망 장소"),
                proposalCurriculumParser.parse(
                        requiredText(answers, ProposalFormSeed.QITEM_CURRICULUM, "커리큘럼")));
    }

    /*
     * 검토 화면용 미리보기. **여기서 예외를 던지지 않는다** — 파싱에 실패한 응답이야말로
     * 검토자가 사유를 봐야 하는 응답인데, 상세 조회가 500이면 그 화면을 열 수조차 없다.
     * 대신 실패 사유를 값으로 실어 내리고, 그 문장은 승인을 눌렀을 때 나올 400의 문장과 같다.
     */
    public AcademicProgramPreviewResponse preview(ResponseContent content) {
        try {
            return AcademicProgramPreviewResponse.of(parse(content));
        } catch (GeneralException ex) {
            return AcademicProgramPreviewResponse.failed(ex.getMessage());
        }
    }

    /*
     * 문자열 답 하나를 꺼낸다. 다중선택(List)은 여기 오지 않는다 — 기획안 폼에는 다중선택
     * 문항이 없고, 그럼에도 형이 어긋나면 추측하지 않고 거절한다(rspns_cn은 JSONB라 DB가
     * 모양을 보장하지 않는다).
     */
    private static String text(Map<String, Object> answers, String qitemId) {
        Object value = answers.get(qitemId);
        if (value == null) {
            return null;
        }
        if (!(value instanceof CharSequence characters)) {
            throw failure("\"" + qitemId + "\" 문항의 답을 읽을 수 없습니다.");
        }
        String answer = characters.toString().trim();
        return answer.isEmpty() ? null : answer;
    }

    private static String requiredText(Map<String, Object> answers, String qitemId, String label) {
        String answer = text(answers, qitemId);
        if (answer == null) {
            throw failure(label + "이(가) 비어 있습니다.");
        }
        return answer;
    }

    private static String optionalText(Map<String, Object> answers, String qitemId) {
        return text(answers, qitemId);
    }

    private static LocalDate requiredDate(
            Map<String, Object> answers, String qitemId, String label) {
        String answer = requiredText(answers, qitemId, label);
        try {
            return LocalDate.parse(answer);
        } catch (DateTimeParseException ex) {
            throw failure(label + "을(를) 날짜로 읽을 수 없습니다: \"" + answer + "\" (예: 2026-03-05)");
        }
    }

    /*
     * 정원. 문항이 SHORT_TEXT + 정규식 ^[0-9]+$라(QuestionItemType에 숫자 유형이 없다) 제출
     * 시점에 숫자로 걸러지지만, 그 정규식은 운영진이 문항을 다듬으며 지울 수 있는 값이라
     * 여기서 다시 본다 — 계약이 잠그는 것은 이 문항의 존재조차 아니다(선택 문항이다).
     */
    private static Integer optionalCount(
            Map<String, Object> answers, String qitemId, String label) {
        String answer = text(answers, qitemId);
        if (answer == null) {
            return null;
        }
        try {
            int count = Integer.parseInt(answer);
            if (count < 0) {
                throw failure(label + "은(는) 0 이상이어야 합니다.");
            }
            return count;
        } catch (NumberFormatException ex) {
            throw failure(label + "을(를) 숫자로 읽을 수 없습니다: \"" + answer + "\"");
        }
    }

    private static String limited(String value, int maxLength, String label) {
        if (value != null && value.length() > maxLength) {
            throw failure(label + "이(가) " + maxLength + "자를 넘습니다.");
        }
        return value;
    }

    private AcademicProgramTypeEntity resolveType(String typeName) {
        return academicProgramTypeRepository
                .findFirstByNameOrderByDisplayOrderAscCodeAsc(typeName)
                .orElseThrow(
                        () ->
                                failure(
                                        "\""
                                                + typeName
                                                + "\"에 해당하는 학술 활동 유형이 없습니다."
                                                + " 폼의 유형 선택지와 기준정보의 유형 이름이 어긋났습니다."));
    }

    private static GeneralException failure(String reason) {
        return new GeneralException(AcademicProgramErrorCode.PROPOSAL_MIGRATION_FAILED, reason);
    }
}
