package org.sscc.ssccopsserver.domain.content.code;

/*
 * 포스트 분류 (ssccops#381 · ADR-0038). cntnt_post.cntnt_clsf_cd에 문자열로 저장된다.
 *
 * 행사 분류(event_clsf — 운영진이 화면에서 늘리는 테이블)와 달리 **고정 enum**이다. www의
 * 아카이브 탭이 이 셋으로 굳어 있고(학술·행사·뉴스), 값이 늘면 탭도 늘어야 하므로 «화면에서
 * 추가»가 성립하지 않는다. 늘리려면 enum·CHECK 제약(마이그레이션)·www 탭을 함께 고친다.
 */
public enum ContentCategory {

    /** 학술 — 스터디·프로젝트 결과 */
    ACADEMIC,

    /** 행사 — 세미나·홈커밍·MT 갈무리. from-event로 만든 포스트의 기본값 */
    EVENT,

    /** 뉴스 — 수상·공지·동아리 소식 */
    NEWS
}
