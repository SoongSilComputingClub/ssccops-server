package org.sscc.ssccopsserver.domain.share.service;

/*
 * 미리보기 한 건 (ssccops#200). 제공자가 돌려주고 공유 도메인이 그대로 응답에 싣는다.
 *
 * **시간에 따라 변하는 값을 담지 않는다.** 메신저는 OG를 한 번 캐싱하면 갱신하지 않아 카드가
 * 굳으므로, 상태·진행률·마감일을 실으면 마감된 뒤에도 "진행 중"이라 말하는 카드가 방에 남는다
 * (ssccops#194 제약 ②). 그래서 이 record에는 그런 값을 담을 자리 자체가 없다 — 제공자가
 * 실수로 실을 수도 없게 하는 것이 요점이다.
 *
 * @param title 대상의 제목. 비어 있을 수 없다
 * @param summary 요약에 쓸 본문. 없으면 null이며 **서버가 대체 문구를 만들지 않는다**
 */
public record SharePreview(String title, String summary) {}
