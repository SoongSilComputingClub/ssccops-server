package org.sscc.ssccopsserver.domain.event.code;

/*
 * event_ptcp_stts_hstry.chg_path_se_cd — 참가 상태를 **어느 화면이** 바꿨나 (#612 · ADR-0042).
 *
 * 명단(event_ptcp)을 바꾸는 길은 셋이고 자격이 다 다르다 — 행사 운영자(EVENT_MANAGE) · 학술국장의
 * 모집 선발 · 스터디장/학술국장의 팀원 관리. «누가 이 사람을 뺐나»를 물을 때 수행자만으로는 그
 * 사람이 어느 권한으로 한 일인지 알 수 없다(학술국장은 셋 다 쓸 수 있다). 그래서 이력이 경로를 함께
 * 적는다.
 *
 * 값은 **부르는 쪽이 정한다** — 행사 도메인은 학술을 모른다(DomainCycleTest). 행사 참가자 API만
 * 행사 서비스가 스스로 채우고, 나머지 둘은 학술 서비스가 넘긴다.
 *
 * 값을 늘리면 chg_path_se_cd CHECK를 새 마이그레이션으로 넓힌다(V28) —
 * FlywayMigrationValidateTest.checkConstraintsMatchTheirEnums가 대조한다.
 */
public enum EventParticipantChangePath {

    /** 행사 참가자 API(/v1/events/{eventId}/participants) — EVENT_MANAGE 운영자 */
    EVENT_PARTICIPANTS,

    /** 학술 모집 선발(POST .../recruitment/select) — 신청서 기준 · 학술국장 */
    RECRUITMENT_SELECTION,

    /** 학술 팀원 관리(POST·PATCH .../members) — 신청서 없이 · 스터디장/학술국장 (#612) */
    TEAM_MEMBERS
}
