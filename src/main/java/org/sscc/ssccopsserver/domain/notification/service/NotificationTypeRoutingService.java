package org.sscc.ssccopsserver.domain.notification.service;

import java.util.List;

import org.sscc.ssccopsserver.domain.notification.code.NotificationApp;
import org.sscc.ssccopsserver.domain.notification.dto.NotificationTypeRouteResponse;

/*
 * 알림 수신 앱 기준표의 조회·수정 (#535 · ssccops#465 · ADR-0047).
 *
 * 읽기 판정(발송·목록)은 `NotificationRoutingPolicy`가 하고 이쪽은 **편집 화면의 계약**이다.
 * 정책 클래스에 쓰기를 얹지 않은 것은 그쪽이 요청 스레드 밖(발송)에서도 불리는 캐시 앞단이라,
 * 감사 로그·권한·유형 해석 같은 «요청의 일»을 섞으면 두 층이 한 클래스에 겹치기 때문이다.
 */
public interface NotificationTypeRoutingService {

    /** 유형 전부(enum 선언 순) × 그 유형의 수신 앱. 기준표에 없는 유형은 «보낸 앱을 따른다»로 온다 */
    List<NotificationTypeRouteResponse> listTypes();

    /**
     * 그 유형의 수신 앱을 통째로 바꾼다. 없는 유형은 404, 빈 목록은 400(ADR-0047 «최소 한 앱»).
     *
     * <p>바꾼 뒤 캐시를 비우므로 **다음 조회·발송부터 곧바로 반영된다.**
     */
    NotificationTypeRouteResponse replaceApps(String typeCode, List<NotificationApp> apps);
}
