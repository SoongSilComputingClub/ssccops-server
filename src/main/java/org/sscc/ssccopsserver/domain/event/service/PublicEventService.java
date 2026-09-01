package org.sscc.ssccopsserver.domain.event.service;

import java.util.List;

import org.sscc.ssccopsserver.domain.event.dto.PublicEventDetailResponse;
import org.sscc.ssccopsserver.domain.event.dto.PublicEventSummaryResponse;

/*
 * 공개(익명) 행사 조회 (ssccops#143 · D1).
 *
 * 운영자용 EventService와 서비스를 나눈다. 폼 도메인은 반대로 응답자용과 운영자용이
 * FormResponseService 하나를 쓰는데(같은 form_rspns_hstry 행을 다루므로 "DRAFT는 심사 대상이
 * 아니다" 같은 규칙이 두 벌이 되면 안 된다), 여기는 사정이 다르다 — 공개 경로가 하는 일은
 * 조회 둘뿐이고 그 둘의 규칙은 운영자 경로에 **없는 규칙**이다("PUBLISHED만 보인다"·"그 밖은
 * 존재하지 않는다"). 한 서비스에 담으면 메서드마다 상태 필터를 다시 붙여야 하고, 한 번
 * 빠뜨리는 것으로 작성 중인 행사가 익명에게 나간다.
 *
 * 반대로 **파생 판정은 하나도 새로 만들지 않는다** — 진행 단계는 EventPhasePolicy, 모집 배지는
 * EventReceiptPolicy를 부르기만 한다. 그 둘이 운영자 화면과 공개 화면이 같은 행사를 같은 값으로
 * 말하게 하는 자리다.
 */
public interface PublicEventService {

    /*
     * 공개 행사 목록. **PUBLISHED만** 나온다 — DRAFT·ARCHIVED는 필터가 아니라 이 메서드의 전제라
     * 상태 파라미터 자체가 없다. 분류(eventClsfCd)만 선택 필터다.
     */
    List<PublicEventSummaryResponse> getPublishedEvents(String classificationCode);

    /*
     * 공개 행사 상세. DRAFT·ARCHIVED와 없는 행사는 **모두 404 EVENT_NOT_FOUND**다 — 403으로
     * 나누면 "그 번호에 무엇인가 있다"는 사실이 새어 나가고, 보관한 행사가 여전히 존재한다는
     * 것도 알려 줄 이유가 없다 (폼 응답의 범위 검사가 코드를 나누지 않는 것과 같은 판단).
     */
    PublicEventDetailResponse getPublishedEvent(Long eventId);

    /*
     * 그 행사가 익명에게 보이는가 — 보이지 않으면 404 EVENT_NOT_FOUND로 끊는다 (#208).
     *
     * 행사 이미지 리다이렉트(GET /public/v1/events/{eventId}/images/{fileName})가 서명을
     * 만들기 전에 부른다. 상세와 **같은 판정**을 쓰는 것이 요점이다 — 이미지 쪽에 판정을 한 벌
     * 더 적으면 "학술 event는 접수 중일 때만 공개한다"(#187) 같은 규칙이 한쪽에만 반영되고,
     * 그 순간 상세는 404인데 포스터는 열리는 상태가 된다.
     *
     * 상세(getPublishedEvent)를 부르지 않는 이유는 그쪽이 확정 인원 집계까지 하기 때문이다.
     * 이미지 한 장을 내주는 데 필요한 것은 "보이는가" 하나뿐이다.
     */
    void requirePublishedEvent(Long eventId);
}
