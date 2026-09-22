package org.sscc.ssccopsserver.domain.notification.service;

import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.notification.dto.PushConfigResponse;
import org.sscc.ssccopsserver.domain.notification.dto.PushSubscriptionRequest;

/*
 * 푸시 구독 등록·해지 (ssccops#446). 자기 구독만 다룬다 — 인가는 없고 인증만 있다.
 */
public interface PushSubscriptionService {

    /** 브라우저가 구독할 때 넣을 VAPID 공개키. 발송기가 꺼져 있으면 publicKey가 null */
    PushConfigResponse config();

    /**
     * 구독을 등록한다. 같은 endpoint가 있으면 그 행을 갱신한다(회원이 바뀌었으면 회원도).
     *
     * @return 만들어졌는지(201) 갱신됐는지(200)와 식별자
     */
    SubscribeResult subscribe(MemberEntity member, PushSubscriptionRequest request);

    /**
     * 구독을 지운다. 없거나 남의 구독이면 아무 일도 하지 않는다 — 결과(그 브라우저로 안 간다)는 같고, 로그아웃 경로에서 부르는 호출이라 실패로 보일 이유가
     * 없다(204 언제나).
     */
    void unsubscribe(Long memberId, String endpoint);

    record SubscribeResult(Long subscriptionId, boolean created) {}
}
