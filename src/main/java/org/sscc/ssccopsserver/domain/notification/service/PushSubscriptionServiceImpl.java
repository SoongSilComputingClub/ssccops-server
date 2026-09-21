package org.sscc.ssccopsserver.domain.notification.service;

import java.time.Instant;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.notification.dto.PushConfigResponse;
import org.sscc.ssccopsserver.domain.notification.dto.PushSubscriptionRequest;
import org.sscc.ssccopsserver.domain.notification.entity.PushSubscriptionEntity;
import org.sscc.ssccopsserver.domain.notification.repository.PushSubscriptionRepository;

import lombok.RequiredArgsConstructor;

/*
 * 푸시 구독의 구현 (ssccops#446 · ADR-0045).
 *
 * **endpoint가 곧 구독의 정체성이다.** 브라우저는 같은 구독을 여러 번 보낼 수 있고(서비스워커
 * 재등록 · 앱 재시작 · 키 갱신), 그때마다 새 행을 만들면 죽은 행이 쌓여 발송마다 404를 받는다.
 * 그래서 등록은 upsert이고 «만들었다»와 «갱신했다»를 상태 코드로 가른다(201 · 200).
 *
 * 구독 값(p256dh·auth)의 형식은 여기서 검사하지 않는다 — 깨진 값은 발송기가 첫 발송에서 GONE으로
 * 판정해 지운다(`VapidWebPushSender`). 등록 시점에 P-256 검증을 넣으면 발송기가 꺼진 환경(Noop)
 * 에서도 같은 검증이 필요해 두 벌이 된다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PushSubscriptionServiceImpl implements PushSubscriptionService {

    private final PushSubscriptionRepository pushSubscriptionRepository;
    private final WebPushSender webPushSender;

    @Override
    public PushConfigResponse config() {
        return new PushConfigResponse(webPushSender.publicKey().orElse(null));
    }

    @Override
    @Transactional
    public SubscribeResult subscribe(MemberEntity member, PushSubscriptionRequest request) {
        Instant expiresAt = toInstant(request.expirationTime());
        return pushSubscriptionRepository
                .findByEndpoint(request.endpoint())
                .map(
                        existing -> {
                            existing.renew(
                                    member,
                                    request.app(),
                                    request.keys().p256dh(),
                                    request.keys().auth(),
                                    expiresAt);
                            return new SubscribeResult(existing.getId(), false);
                        })
                .orElseGet(
                        () -> {
                            PushSubscriptionEntity saved =
                                    pushSubscriptionRepository.save(
                                            PushSubscriptionEntity.subscribe(
                                                    member,
                                                    request.app(),
                                                    request.endpoint(),
                                                    request.keys().p256dh(),
                                                    request.keys().auth(),
                                                    expiresAt));
                            return new SubscribeResult(saved.getId(), true);
                        });
    }

    @Override
    @Transactional
    public void unsubscribe(Long memberId, String endpoint) {
        pushSubscriptionRepository
                .findByEndpoint(endpoint)
                .filter(subscription -> subscription.isOwnedBy(memberId))
                .ifPresent(pushSubscriptionRepository::delete);
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
