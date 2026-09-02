package org.sscc.ssccopsserver.domain.event.service;

import java.util.List;

import org.sscc.ssccopsserver.domain.event.dto.MyApplicationResponse;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/*
 * 신청자 본인의 신청 현황 조회 (ssccops#145 · GET /v1/events/my-applications).
 *
 * 운영자용 EventParticipationService와 서비스를 나눈다 — 그쪽은 남의 신청을 심사하고 명단을
 * 운영하는 일이고 이쪽은 자기 것만 본다. 대상 회원이 언제나 인증 주체라는 것이 이 서비스의
 * 전제이며, 그래서 회원을 식별자가 아니라 엔티티로 받는다(호출부가 @CurrentMember 말고 다른
 * 회원을 넣을 자리를 만들지 않는다).
 *
 * **철회는 여기 없다.** 허용 범위가 미결 결정(SoongSilComputingClub/ssccops#138 — 행사 연결 폼
 * 한정인가 폼 도메인 전반인가)에 걸려 있어 이번 범위에서 제외했다.
 */
public interface MyApplicationService {

    List<MyApplicationResponse> getMyApplications(MemberEntity member);
}
