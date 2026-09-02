package org.sscc.ssccopsserver.domain.academicprogram.dto;

import java.util.List;

/*
 * 검토 화면이 승인 전에 보는 이관 미리보기 (#150 · 폼 응답 상세의 academicProgramPreview).
 * sys_form_cd = 'PROPOSAL'인 응답에서만 채워지고 그 밖의 응답에서는 필드 자체가 null이다.
 *
 * ── 왜 미리보기가 필요한가 ────────────────────────────────────
 * 커리큘럼은 자유 텍스트로 접수되고(정규식을 걸지 않는다, ssccops#131) 승인 시점에 회차로
 * 쪼개진다. 검토자가 그 결과를 보지 못한 채 승인하면 "형식이 어긋난 줄"은 400으로 되돌아오고,
 * 무엇이 잘못됐는지는 실패한 뒤에야 알게 된다. 여기 실린 값은 **실제 이관이 만들 값 그 자체**다
 * — 같은 파서(ProposalResponseParser)가 만든다.
 *
 * ── migratable · failureReason ────────────────────────────────
 * 파싱에 실패한 응답이야말로 검토자가 봐야 하는 응답이라, 실패를 예외로 던지지 않고 값으로
 * 싣는다(상세 조회가 500이 되면 그 화면을 열 수조차 없다). migratable = false면 지금 승인을
 * 누르면 400 PROPOSAL_MIGRATION_FAILED가 나며, failureReason이 그 400과 **같은 문장**이다 —
 * 검토자가 할 일은 재시도가 아니라 그 사유로 수정요청(#141)을 보내는 것이다.
 *
 * 성공한 경우 failureReason은 null이고, 실패한 경우 typeCd는 null·curriculumItems는 빈 배열이다
 * (일부만 채워 내리면 화면이 "절반은 옮겨진다"로 읽는다 — 이관은 전부 아니면 전무다).
 *
 * 필드를 typeCd·curriculumItems 둘로 좁힌 것은 나머지(제목·기간·목표·정원)가 응답 원문
 * (rspnsCn)에 그대로 있어 화면이 이미 그리고 있기 때문이다. 여기 실을 값은 **파싱을 거쳐야만
 * 알 수 있는 것**뿐이다 — 원문을 한 번 더 복사해 내리면 같은 사실이 한 응답 안에 두 벌이 된다.
 */
public record AcademicProgramPreviewResponse(
        String typeCd,
        List<CurriculumItemDraft> curriculumItems,
        boolean migratable,
        String failureReason) {

    public static AcademicProgramPreviewResponse of(ProposalDraft draft) {
        return new AcademicProgramPreviewResponse(
                draft.type().getCode(), draft.curriculumItems(), true, null);
    }

    public static AcademicProgramPreviewResponse failed(String reason) {
        return new AcademicProgramPreviewResponse(null, List.of(), false, reason);
    }
}
