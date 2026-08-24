package org.sscc.ssccopsserver.domain.event.service;

import java.util.List;

import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;
import org.sscc.ssccopsserver.domain.event.dto.EventParticipantMutationResponse;
import org.sscc.ssccopsserver.domain.event.dto.EventParticipantRegisterRequest;
import org.sscc.ssccopsserver.domain.event.dto.EventParticipantResponse;
import org.sscc.ssccopsserver.domain.event.dto.EventParticipantStatusChangeRequest;
import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseSummaryResponse;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/*
 * 행사 신청 목록과 참가자 명단 (ssccops#146 · D5·D8·D14·D16).
 *
 * 운영자의 일은 셋이다 — 신청을 보고, 심사하고, 수락한 사람을 명단에 올린다. **가운데 하나는
 * 여기 없다.** 심사는 폼 응답 검토 API(#141)가 이미 하는 일이고 상태 전이표·검토 의견 필수
 * 규칙·처리 이력이 전부 그쪽에 있다 — 행사 경로에 심사를 하나 더 만들면 같은
 * form_rspns_hstry 행을 두 규칙이 다루게 되고, 한쪽만 고쳐지는 날 이력 없는 승인이 생긴다.
 *
 * 신청 목록도 규칙을 복제하지 않는다. FormResponseService.getResponses에 위임하며, 이쪽이
 * 하는 일은 "행사 → 연결 폼"을 푸는 것과 권한 문을 하나 더 여는 것뿐이다(D8 — 행사 경유
 * 접근은 EVENT_MANAGE로 허용한다. 행사 운영자가 자기 행사의 신청을 보려고 폼 전체 권한을
 * 받아야 한다면 그 권한이 다른 모든 폼의 지원서까지 함께 연다).
 */
public interface EventParticipationService {

    /*
     * 신청 목록 = 연결 폼의 응답 목록. 폼이 없는 행사는 빈 목록이 아니라 409
     * EVENT_HAS_NO_FORM이다 — 빈 배열은 "아직 아무도 신청하지 않았다"로 읽히는데 실제로는
     * 신청을 받을 수단 자체가 없다.
     *
     * statusCode의 기본값도 폼 쪽 규칙 그대로다(작성 중을 뺀 전부).
     */
    List<FormResponseSummaryResponse> getApplications(Long eventId, ResponseStatus statusCode);

    /** 참가자 명단. 상태 필터는 선택이며 미지정은 전체(취소 포함)다 — 명단은 영구 보존이다(D16) */
    List<EventParticipantResponse> getParticipants(Long eventId, EventParticipantStatus statusCode);

    /*
     * 참가자 등록. 근거는 응답(formRspnsId) 또는 회원(mbrId) 하나이며 상호 배타다.
     *
     * 응답 기반은 그 응답이 **이 행사의 연결 폼 응답**이고 **ACCEPTED**여야 한다. 정원 초과는
     * 막지 않고(D5) 응답에 숫자로 싣고, 탈퇴·제명 회원도 막지 않고 warnings로 알린다(§8-5).
     * 등록자(rgtr_mbr_id)는 요청이 아니라 인증 주체에서 온다.
     */
    EventParticipantMutationResponse registerParticipant(
            Long eventId, EventParticipantRegisterRequest request, MemberEntity registrant);

    /*
     * 참가 상태 전이 (D14). 허용은 승격(WAITLISTED→CONFIRMED)과 취소(CONFIRMED→CANCELLED)
     * 둘뿐이며 전이표는 EventParticipantEntity.changeStatus가 갖는다.
     *
     * **행을 지우는 경로는 두지 않는다** — 명단은 활동 이력으로 영구 보존한다(D16).
     */
    EventParticipantMutationResponse changeParticipantStatus(
            Long eventId, Long eventParticipantId, EventParticipantStatusChangeRequest request);
}
