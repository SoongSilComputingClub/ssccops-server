package org.sscc.ssccopsserver.domain.academicprogram.dto;

import java.time.OffsetDateTime;

import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;
import org.sscc.ssccopsserver.domain.event.entity.EventParticipantEntity;
import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseSummaryResponse;
import org.sscc.ssccopsserver.domain.form.dto.ResponseMemberSummary;

/*
 * 모집 신청자 한 줄 (#198 · GET .../recruitment/applications).
 *
 * 폼 응답 요약(FormResponseSummaryResponse)에 **참가 상태**를 얹은 모양이다. 그 DTO를 그대로
 * 내리던 것이 #138인데, 선발이 심사와 등록을 함께 하므로 확정이든 대기든 응답은 똑같이
 * ACCEPTED가 된다 — 화면은 "선발됐다"까지만 알고 확정인지 대기인지 몰라, 대기로 뽑은
 * 신청자가 확정자와 똑같이 '선발 완료'로 표시됐다(ssccops-web#209).
 *
 * **폼 도메인의 DTO에 참가 상태를 더하지 않았다.** event_ptcp는 폼이 모르는 개념이고, 그
 * 필드는 신청자 목록을 제외한 모든 응답 목록(운영자용 목록·내 응답 목록·검토 처리 응답)에서
 * 언제나 null이 된다 — 어느 화면에서 뜻이 있는 값인지를 DTO가 말해 주지 못하게 된다. 대신
 * 모집 화면 전용 응답을 여기 두고 폼 쪽 값은 요약 DTO에서 그대로 옮긴다(옮기는 자리는
 * AcademicProgramRecruitmentServiceImpl 하나이며, 두 record의 필드가 어긋나지 않는지는
 * RecruitmentApplicationResponseTest가 못 박는다).
 *
 * **참가 상태는 응답이 아니라 회원으로 잇는다.** 명단의 열쇠가 (event_id, mbr_id) UNIQUE라
 * 한 사람은 한 활동에 한 줄이고, "이 신청자가 지금 확정인가 대기인가"의 답도 하나다. 같은
 * 회원이 낸 응답이 여러 줄이면(반려 뒤 새 신청, #192) 그 줄들에 같은 상태가 함께 실린다 —
 * 응답으로 이으면 명단 행이 가리키는 응답만 상태를 얻고, 재선발로 갱신된 자리는 다른 응답에
 * 달려 있어 방금 확정한 신청자가 '미선발'로 보인다.
 *
 * **아직 선발되지 않았으면 eventPtcpId·ptcpSttsCd 둘 다 null이며 서버가 대체값을 만들지
 * 않는다** — 웹이 그때 "미선발"로 읽는다. 취소(CANCELLED)된 참가자는 그대로 CANCELLED다
 * (명단은 활동 이력으로 영구 보존한다, D16).
 */
public record RecruitmentApplicationResponse(
        Long formRspnsId,
        int rspnsSeq,
        String responseTitle,
        ResponseStatus rspnsSttsCd,
        OffsetDateTime sbmsnDt,
        ResponseMemberSummary member,
        Long eventPtcpId,
        EventParticipantStatus ptcpSttsCd) {

    /** participant가 null이면 아직 선발되지 않은 신청자다 */
    public static RecruitmentApplicationResponse of(
            FormResponseSummaryResponse application, EventParticipantEntity participant) {
        return new RecruitmentApplicationResponse(
                application.formRspnsId(),
                application.rspnsSeq(),
                application.responseTitle(),
                application.rspnsSttsCd(),
                application.sbmsnDt(),
                application.member(),
                participant == null ? null : participant.getId(),
                participant == null ? null : participant.getStatus());
    }
}
