package org.sscc.ssccopsserver.global.audit;

/*
 * 감사 대상 사건의 이름 (ssccops#299 · ADR-0024).
 *
 * `event.action` 값이다. `{도메인}.{대상}.{동사}` snake_case — 기계가 읽는 이름이고 사람이 읽는
 * 문장은 `message`에 따로 간다. 여기 없는 사건은 감사 로그가 아니다: 새 사건을 남기려면 한 줄을
 * 더하고 그 자리에서 AuditLog를 부른다 — 자유 문자열을 받지 않는 것은 같은 사건이 자리마다 다른
 * 이름으로 남아 Kibana에서 합쳐지지 않는 것을 막기 위해서다.
 *
 * RFP 「로그인 · 개인정보 조회·수정 · 권한 · 승인 · 게시 · 내보내기」를 이 시스템의 실제 경로에
 * 대응한 1차 목록이다. 로그인 자체는 Supabase에서 일어나 서버가 모른다 — 서버가 아는 첫 사건은
 * 가입·연결이다.
 */
public enum AuditAction {
    MEMBER_SIGNUP("member.signup", "member"),
    MEMBER_LINK("member.link", "member"),
    /** 운영진의 회원 상세 조회. 본인 조회는 남기지 않는다 — 부르는 쪽이 거른다 */
    MEMBER_DETAIL_READ("member.detail.read", "member"),
    /** 값이 아니라 바뀐 필드 이름만 남긴다 (audit.changed_fields) */
    MEMBER_PROFILE_UPDATE("member.profile.update", "member"),
    MEMBER_GRADE_CHANGE("member.grade.change", "member"),
    MEMBER_STATUS_CHANGE("member.status.change", "member"),
    MEMBER_ROLE_GRANT("member.role.grant", "member"),
    MEMBER_ROLE_REVOKE("member.role.revoke", "member"),
    ROLE_AUTHORITY_CHANGE("role.authority.change", "role"),
    /** 하드 삭제(ADR-0021). 409로 막힌 시도도 failure로 남긴다 */
    MEMBER_DELETE("member.delete", "member"),
    MEMBER_IMPORT("member.import", "member_import"),
    SUBWORK_TRANSITION("subwork.transition", "sub_work"),
    /** 운영 건 첨부 삭제 (#493) — 추가는 감사하지 않는다(파일이 곧 증거), 지운 것만 남긴다 */
    OPERATION_ATTACHMENT_DELETE("operation.attachment.delete", "file_rfrnc"),
    SUBWORK_APPROVAL_VOTE("subwork.approval.vote", "sub_work"),
    FORM_RESPONSE_REVIEW("form.response.review", "form_response"),
    ACADEMIC_PROGRAM_TRANSITION("academic.program.transition", "academic_program"),
    ACADEMIC_SESSION_TRANSITION("academic.session.transition", "session"),
    EVENT_STATUS_CHANGE("event.status.change", "event"),
    FORM_STATUS_CHANGE("form.status.change", "form"),

    /*
     * 콘텐츠 게시·게시 취소 (ssccops#381 · ADR-0038). 익명에게 «무엇이 언제 열리고 닫혔나»가
     * 곧 이 사건이라 남긴다. 작성·수정은 남기지 않는다 — 그쪽은 이력 테이블(cntnt_*_hstry)이
     * 본문째 들고 있고, 감사 로그에는 본문 값이 실리지 않는다(ADR-0024). 대상은 식별자와
     * 결과 상태뿐이다.
     */
    CONTENT_PAGE_PUBLISH("content.page.publish", "content_page"),
    CONTENT_PAGE_UNPUBLISH("content.page.unpublish", "content_page"),
    CONTENT_POST_PUBLISH("content.post.publish", "content_post"),
    CONTENT_POST_UNPUBLISH("content.post.unpublish", "content_post"),
    /**
     * @RequireAuthority 거절. 누가 무엇을 시도했는가
     */
    AUTHZ_DENY("authz.deny", "handler");

    private final String code;
    private final String targetType;

    AuditAction(String code, String targetType) {
        this.code = code;
        this.targetType = targetType;
    }

    public String code() {
        return code;
    }

    /** `audit.target.type`의 기본값. 사건마다 대상 종류가 정해져 있어 부르는 쪽이 적지 않는다 */
    public String targetType() {
        return targetType;
    }
}
