package org.sscc.ssccopsserver.domain.operation.entity;

/*
 * mtg.mtg_stts_cd — 회의 상태. API 정의서 02_상태_전이 TR-M1~M4를 그대로 옮겼다.
 * 프론트 codes.ts의 MtgSttsCd와 이름을 맞춘다.
 */
public enum MeetingStatus {
    SCHEDULED, // 예정
    IN_PROGRESS, // 진행
    MINUTES, // 회의록작성
    CLOSED, // 종료
    CANCELED // 취소
}
