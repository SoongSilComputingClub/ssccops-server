package org.sscc.ssccopsserver.domain.operation.entity;

/*
 * mtg.atnd_trgt_cd — 참석 대상. 전체(ALL)·국장단(DIRECTORS)·임시소집(AD_HOC) 3종이며
 * 프론트 codes.ts의 AtndTrgtCd와 이름을 맞춘다.
 */
public enum AttendeeScope {
    ALL, // 전체
    DIRECTORS, // 국장단
    AD_HOC // 임시소집
}
