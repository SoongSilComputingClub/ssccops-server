package org.sscc.ssccopsserver.domain.notification.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.notification.code.NotificationApp;
import org.sscc.ssccopsserver.domain.notification.code.NotificationTargetType;
import org.sscc.ssccopsserver.domain.notification.code.NotificationType;
import org.sscc.ssccopsserver.domain.notification.dto.TestNotificationResponse;
import org.sscc.ssccopsserver.domain.notification.entity.NotificationEntity;
import org.sscc.ssccopsserver.domain.notification.repository.NotificationRepository;

import lombok.RequiredArgsConstructor;

/*
 * 테스트 알림 — 자기에게 알림 행 하나와 푸시 (#528 · ssccops#454).
 *
 * 푸시를 켠 사람이 «정말 오나»를 바로 확인할 길이 없었다 — 남이 검토 요청을 하거나 09:00
 * 스케줄러를 기다려야 했다(dev QA). «내 정보»의 버튼이 이 경로를 부른다.
 *
 * **동기다.** 사건 알림(리스너)은 커밋 뒤 비동기로 보내지만 여기는 «몇 대에 갔나»를 응답에
 * 실어야 하므로 요청 스레드에서 보내고 기다린다. 발송기의 왕복 상한(구독당 10초)이 곧 이 요청의
 * 상한이며, 1분 3회 한도(`TestNotificationRateLimiter`)가 그 시간을 연타로 곱하지 못하게 한다.
 *
 * **트랜잭션 밖이다.** 알림 행은 리포지토리 `save` 자체의 트랜잭션으로 커밋되고(마감 스케줄러와
 * 같은 모양), 푸시는 그 뒤 DB 커넥션을 쥐지 않은 채 나간다. 회원 엔티티는 `@CurrentMember`의
 * 준영속 인스턴스 그대로다 — ManyToOne 참조에는 식별자만 쓰이므로 다시 읽지 않는다
 * (`PushSubscriptionServiceImpl`과 같다).
 *
 * `pushed`는 **푸시 서비스가 받아 준 구독 수**(`DELIVERED`)다. 발송기가 꺼진 환경(Noop · 키 없음)
 * 에서는 0이고 알림 행만 남는다 — 그것이 «푸시 설정이 안 됐다»를 화면이 보이는 길이다. 죽은
 * 구독(GONE)은 발송 중 지워지며 세지 않는다.
 *
 * 대상은 (`MEMBER`, 본인)이다 — 이 알림이 가리키는 것은 어떤 건이 아니라 받는 사람의 기기 설정
 * 자체다. 링크는 그 앱의 «내 정보»(ADMIN·LMS `/my` · WWW `/me`) — 누르면 방금 누른 버튼이 있는
 * 화면으로 돌아온다.
 */
@Service
@RequiredArgsConstructor
public class TestNotificationService {

    static final String TITLE = "[테스트] 알림이 잘 옵니다";
    static final String BODY = "이 기기로 푸시가 오면 설정이 끝난 것입니다";

    private final TestNotificationRateLimiter rateLimiter;
    private final NotificationRepository notificationRepository;
    private final PushDispatcher pushDispatcher;

    public TestNotificationResponse send(MemberEntity member, NotificationApp app) {
        rateLimiter.requireWithinQuota(member.getId());

        NotificationEntity saved =
                notificationRepository.save(
                        NotificationEntity.create(
                                member,
                                NotificationType.TEST,
                                TITLE,
                                BODY,
                                app,
                                linkPath(app),
                                NotificationTargetType.MEMBER,
                                member.getId(),
                                null));
        int pushed = pushDispatcher.dispatch(List.of(CreatedNotification.of(saved)));
        return new TestNotificationResponse(saved.getId(), pushed);
    }

    /** 그 앱의 «내 정보» 경로. 어드민·lms는 `/my`, www는 `/me`다 — 각 앱의 실제 라우트 */
    static String linkPath(NotificationApp app) {
        return switch (app) {
            case ADMIN, LMS -> "/my";
            case WWW -> "/me";
        };
    }
}
