package org.sscc.ssccopsserver.domain.form.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import org.sscc.ssccopsserver.domain.form.code.FormReceiptStatus;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;

/*
 * 폼 단건 상세 (#32 · GET /v1/forms/{formId}). 폼 상세 화면과 폼 편집 화면이 같이 쓴다 —
 * 편집기는 이 응답을 그대로 초안(draft)으로 받아 고친 뒤 PUT으로 돌려보낸다. 그래서 요청
 * DTO(FormSaveRequest)와 필드 이름이 일치해야 하고, 문항 구성을 통째로 싣는다.
 *
 * 생성자를 중첩 객체가 아니라 creatrMbrId·creatrMbrNm 두 필드로 편 것은 웹 타입의 Form이
 * creatrMbrId만 갖고 이름은 회원 스토어에서 따로 찾고 있어서다. 서버가 이름까지 같이 내리면
 * 그 조회가 사라지고, 식별자는 그대로 남아 있어 기존 코드가 깨지지 않는다.
 *
 * responseCount는 목록과 같은 기준(제출 이상만)이다. 상세 화면은 그 위에 '전체 · 제출 · 수정요청 ·
 * 승인 · 반려' 다섯 숫자를 보여주므로 responseSummary를 함께 내린다 (#37) — 두 값은 같은 집계에서 나오고
 * responseSummary.total과 responseCount는 언제나 같다. 굳이 둘 다 두는 것은 목록(FormSummary)이
 * 이미 responseCount를 쓰고 있어 상세만 이름을 바꾸면 웹이 두 응답을 다르게 읽어야 하기 때문이다.
 *
 * 예전에는 상태별 집계를 응답 조회 API(#37)가 따로 갖는 것으로 미뤄 뒀는데, 그동안 웹은
 * res.responseSummary를 찾지 못해 요약 상자를 늘 0으로 그렸다 — 터지지 않고 조용히 틀리는
 * 종류라 화면만 보고는 알 수 없었다. DRAFT는 네 숫자 어디에도 들어가지 않는다 (#36 규칙).
 *
 * receiptStatus는 목록과 같은 파생 값이다 (#33 · FormReceiptPolicy). 상세 화면의 '접수 시작 /
 * 마감' 버튼 문구는 formSttsCd로 고르지만(전이표가 그 값으로 정의돼 있다), 사용자에게 보이는
 * 배지는 기간까지 반영해야 하므로 두 값을 함께 내린다.
 *
 * sysFormCd·sysYn·qitemVer는 #140에서 더했다. 편집 화면이 시스템 폼임을 알아야 "이 문항은
 * 시스템이 요구한다"를 미리 안내하고 삭제 버튼을 감출 수 있다 — 서버가 400·409로 거절할 수는
 * 있지만, 그것만으로는 사용자가 저장을 누르기 전까지 알 수 없다. qitemVer를 함께 내리는 것은
 * 편집기가 자기가 받아 온 구성이 아직 최신인지 판단할 근거로 쓰기 위해서다.
 *
 * **systemRequiredQitemIds(#155)가 바로 그 "어느 문항을 요구하는가"다.** #140은 시스템 폼임을
 * 알려 주는 데까지만 갔고 정작 계약 문항 목록은 서버 안에 남겨 둔 탓에, 웹(ssccops-web#132)은
 * 저장이 400 SYSTEM_FORM_CONTRACT_VIOLATION으로 터진 뒤 "보낸 구성에서 빠진 문항"을 역산해
 * 잠금을 걸고 있었다. 서버가 거절하므로 데이터가 깨지지는 않았지만, 잠금이 **사후에만** 걸려
 * 운영자는 문항을 지우고 저장을 누른 뒤에야 막혔다는 것을 알았다. 같은 사실의 출처가 두 벌이 된
 * 것도 문제다 — 계약은 서버가 선언하는데 화면은 오류에서 추론했다. 역할별 권한 응답이 직접
 * 부여(grants)와 펼친 결과(effectiveAuthrtCds)를 함께 내리는 것과 같은 이유다(RoleAuthorityResponse).
 *
 * 값은 SystemFormContract.requiredQitemIdsOf 하나에서만 온다. 이 자리에서 다시 계산하지 않고
 * 받아서 모양만 바꾸는 것은, 조립하는 자리가 계약을 다시 읽기 시작하면 저장 경로의 거절 기준과
 * 화면이 잠그는 기준이 갈라질 수 있기 때문이다 — 갈리는 순간 화면은 잠기지 않았는데 서버는 거절하는,
 * 정확히 #155가 없애려는 상황으로 돌아간다. 화면이 미리 잠그는 것은 편의이고 서버의 400이 방어선이다 —
 * 둘 중 하나를 없애지 않는다.
 *
 * 시스템 폼이 아니거나 계약 선언이 없는 코드면 **빈 배열이며 null이 아니다**. 둘을 섞으면
 * 받는 쪽이 "없음"과 "비어 있음"을 구별해야 하는데 둘은 같은 뜻이다(AP-15).
 *
 * Set을 그대로 실지 않고 정렬된 List로 바꾸는 것은 JSON 배열의 순서가 호출마다 뒤집히지
 * 않게 하기 위해서다 — Set.of는 순회 순서를 보장하지 않아 같은 폼을 두 번 조회한 응답이 다를 수 있고,
 * 그러면 응답을 비교해 변경을 판단하는 편집기와 테스트가 이유 없이 요동한다.
 *
 * mltplRspnsYn(#143)을 싣는 것은 편집기가 이 응답을 그대로 초안으로 받아 PUT으로 돌려보내기
 * 때문이다 — 빼면 폼을 한 번 저장할 때마다 다중 응답 설정이 false로 되돌아간다(요청의 생략이
 * false이므로 조용히 꺼진다). 상세를 요청 DTO와 같은 이름으로 맞춰 두는 규칙이 여기서 값을
 * 지킨다.
 *
 * academicProgramId(#190)는 이 폼이 학술 활동(acdm_actv)에 연결돼 있으면 그 활동 id, 아니면
 * null이다. form → event → acdm_actv 역참조로 채운다(AcademicProgramRepository.findIdByFormId).
 * 편집 화면은 이 값이 있으면 접수 기간 입력란을 숨긴다 — 학술 연결 폼의 접수 기간은 "모집
 * 관리"에서만 바꾸고, 폼 편집으로 바꾸면 400 ACADEMIC_FORM_RECEIPT_PERIOD_LOCKED다. 이관 폼의
 * 분류는 "EVENT"라 분류 코드로는 일반 폼과 구별되지 않으므로(#187) 이 조인이 유일한 판별이다.
 *
 * 일시는 AP-12에 따라 Asia/Seoul 오프셋을 포함해 내려준다.
 */
public record FormDetailResponse(
        Long formId,
        String formTtlNm,
        FormStatus formSttsCd,
        FormReceiptStatus receiptStatus,
        OffsetDateTime rcptBgngDt,
        OffsetDateTime rcptEndDt,
        QuestionCompositionContent qitemCpstCn,
        String sysFormCd,
        boolean sysYn,
        int qitemVer,
        List<String> systemRequiredQitemIds,
        boolean mltplRspnsYn,
        Long academicProgramId,
        List<FormLabelSummaryResponse> labels,
        long responseCount,
        FormResponseStatusSummary responseSummary,
        Long creatrMbrId,
        String creatrMbrNm,
        OffsetDateTime crtDt,
        OffsetDateTime mdfcnDt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static FormDetailResponse of(
            FormEntity form,
            FormReceiptStatus receiptStatus,
            List<FormLabelSummaryResponse> labels,
            FormResponseStatusSummary responseSummary,
            Set<String> systemRequiredQitemIds,
            Long academicProgramId) {
        return new FormDetailResponse(
                form.getId(),
                form.getTitle(),
                form.getStatus(),
                receiptStatus,
                toOffsetDateTime(form.getReceiptBeginAt()),
                toOffsetDateTime(form.getReceiptEndAt()),
                form.getQuestionComposition(),
                form.getSystemFormCode(),
                form.isSystemForm(),
                form.getQuestionVersion(),
                systemRequiredQitemIds.stream().sorted().toList(),
                form.isMultipleResponseAllowed(),
                academicProgramId,
                labels,
                responseSummary.total(),
                responseSummary,
                form.getCreator().getId(),
                form.getCreator().getName(),
                toOffsetDateTime(form.getCreatedAt()),
                toOffsetDateTime(form.getUpdatedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
