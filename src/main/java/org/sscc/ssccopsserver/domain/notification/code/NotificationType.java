package org.sscc.ssccopsserver.domain.notification.code;

/*
 * noti.noti_type_cd — 알림의 종류 (ssccops#446 · ADR-0045).
 *
 * 1차 사건은 하위 업무 다섯이다 — 승인 요청·승인·반려는 전이(SubWorkTransitionedEvent)가,
 * 마감 D-1·첫 지연일은 09:00 스케줄러가 만든다. 값 이름은 #446의 API 계약(`type`)과 같다 —
 * 웹이 이 문자열로 아이콘·문구를 가르므로 바꾸면 web Sub-task에 알린다.
 *
 * 2차(#528 · ssccops#453 · ssccops#454)는 **회원이 낸 것의 처리 과정**이다 — 폼 응답 검토 결과 셋
 * (FormResponseReviewedEvent)과 행사 참가 상태 셋(EventParticipantStatusChangedEvent), 그리고 자기
 * 기기를 확인하는 테스트 알림 하나. 앞의 여섯은 `app = WWW`다(내 응답·내 신청의 허브가 www다).
 *
 * **수신 앱은 여기 없다.** ADR-0046이 잠깐 «유형이 targets를 선언한다»였는데 하루 만에 ADR-0047로
 * 대체됐다 — 유형은 사건의 이름일 뿐이고 «어디로 보내는가»는 운영 중에 바뀌는 정책이라
 * 기준표(noti_type_rcpn · NotificationRoutingPolicy)가 든다. **targets 필드를 두지 말 것.**
 *
 * label은 사람이 읽는 이름이지 정책이 아니다 — 어드민 «설정 › 알림 유형» 화면이 체크박스 줄의
 * 제목으로 쓴다(GET /v1/notifications/types). 이 문자열을 화면에 한 벌 더 적으면 유형을 더할 때
 * 두 자리가 갈린다.
 *
 * **값 목록과 CHECK 제약이 정본 한 쌍이다**(V21 → V22 · V13 규칙 ·
 * FlywayMigrationValidateTest.checkConstraintsMatchTheirEnums). 사건을 더할 때 새 마이그레이션으로
 * 제약도 함께 넓힌다 — 회차·출석 승인 사건(lms)이 다음 후보다.
 */
public enum NotificationType {

    /** 검토 요청 → 그 유형의 결재 권한 보유자 전원(요청자 제외) */
    APPROVAL_REQUESTED("승인 요청"),

    /** 승인·완료 → 담당자 */
    APPROVAL_APPROVED("승인"),

    /** 반려 → 담당자 */
    APPROVAL_REJECTED("반려"),

    /** 마감이 내일(D-1) → 담당자. 하루 한 번, noti_key로 한 번만 */
    DEADLINE_DUE("마감 D-1"),

    /** 마감이 어제(첫 지연일) → 담당자. 하루 한 번, noti_key로 한 번만 */
    DEADLINE_OVERDUE("마감 지남"),

    /** 폼 응답 승인 → 응답자 (검토자 본인 응답이면 생략) */
    RESPONSE_ACCEPTED("응답 승인"),

    /** 폼 응답 반려 → 응답자 */
    RESPONSE_REJECTED("응답 반려"),

    /** 폼 응답 수정 요청 → 응답자. «다음 행동»이 있는 사건이라 이 묶음의 핵심이다 */
    RESPONSE_CHANGES_REQUESTED("응답 수정 요청"),

    /** 행사 참가 확정 → 그 회원 (본인이 바꾼 것이면 생략) */
    APPLICATION_CONFIRMED("참가 확정"),

    /** 행사 참가 대기 → 그 회원 */
    APPLICATION_WAITLISTED("참가 대기"),

    /** 행사 참가 취소 → 그 회원 */
    APPLICATION_CANCELLED("참가 취소"),

    /** 테스트 알림 → 호출자 본인. «내 정보»의 버튼이 만든다(POST /v1/notifications/test) */
    TEST("테스트 알림");

    private final String label;

    NotificationType(String label) {
        this.label = label;
    }

    /** 화면에 그대로 나가는 이름. 어드민 편집 화면의 줄 제목이다 (#535) */
    public String label() {
        return label;
    }
}
