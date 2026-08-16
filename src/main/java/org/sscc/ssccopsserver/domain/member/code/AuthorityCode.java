package org.sscc.ssccopsserver.domain.member.code;

/*
 * 코드가 직접 가리키는 권한 코드(#9 · ssccops#68 BR-M20).
 *
 * 권한 자체는 authrt 테이블의 데이터이고 운영진이 화면에서 이름·설명·트리 위치를 고칠 수 있다.
 * 그런데 @RequireAuthority는 컴파일 시점에 값을 하나 골라야 하므로, **코드가 참조하는 부분집합**만
 * 여기 열거한다. 이 enum에 있는 코드는 시드에서 sys_yn = true로 들어가며 삭제·코드 변경이 막힌다
 * (BR-M24 — 화면 조작 한 번으로 인가가 통째로 무력화되는 것을 막는다).
 *
 * 문자열 상수 대신 enum인 것은 오타가 런타임에 "아무도 통과 못 하는 엔드포인트"로 조용히
 * 나타나기 때문이다. 이름이 곧 authrt_cd이므로 name()을 코드값으로 쓴다.
 *
 * 계층(부모-자식)은 여기 담지 않는다 — 트리는 데이터(authrt.up_authrt_cd)가 갖고 운영진이
 * 옮길 수 있으며, enum에 한 벌 더 적으면 두 벌이 갈린다. 펼침 판정은 AuthorityPolicy 한 곳뿐이다.
 */
public enum AuthorityCode {

    /*
     * 최고 관리자 — 트리의 최상위. 아래 전부를 포함한다 (#71 · ssccops#71 BR-M35).
     *
     * 회원이 한 명도 없을 때 최초 가입자에게 부여되는 권한이며, 그 뒤로는 운영진이 화면에서
     * 넘겨준다. 어떤 엔드포인트도 @RequireAuthority(SUPER)를 요구하지 않는다 — "전부 포함"은
     * 트리의 부모-자식 관계가 만들어 내는 결과이지 판정의 예외가 아니다. AuthorityPolicy에
     * SUPER를 특별 취급하는 분기를 넣지 말 것: 웹이 저장 전 미리 보기를 위해 같은 펼침을
     * 트리 간선으로 한 번 더 하므로(entities/authority/model/tree.ts.previewGrants), 서버만
     * 특별 취급하면 체크박스와 실제 부여 결과가 갈린다.
     */
    SUPER,

    /** 임원 — 운영 전반의 묶음. SUPER의 자식이다 */
    EXECUTIVE,

    /** 운영자 — 업무·폼 운영 묶음 */
    OPERATOR,

    /** 업무·하위 업무 관리 */
    WORK_MANAGE,

    /** 업무·하위 업무 조회 전용(#101). WORK_MANAGE의 자식이라 그 보유자는 자동으로 포함한다 */
    WORK_READ,

    /** 회의 등록·조회·상태 전이·안건 관리 */
    MEETING_MANAGE,

    /** 회의 조회 전용(#101). MEETING_MANAGE의 자식이라 그 보유자는 자동으로 포함한다 */
    MEETING_READ,

    /** 회의 안건 등록·수정·철회 전용(#101) — 회의 자체의 생성·전이는 MEETING_MANAGE만의 몫이다 */
    MEETING_AGENDA_WRITE,

    /** 하위 업무 유형 조회 */
    SUB_WORK_TYPE_READ,

    /** 하위 업무 유형 등록·수정 */
    SUB_WORK_TYPE_MANAGE,

    /** 폼 관리 묶음 */
    FORM_MANAGE,

    /** 폼 조회 */
    FORM_READ,

    /** 폼 작성·수정 */
    FORM_WRITE,

    /** 폼 접수 상태 변경 */
    FORM_STATUS_CHANGE,

    /** 폼 라벨 관리 */
    FORM_LABEL_MANAGE,

    /** 폼 응답 조회·심사 */
    RESPONSE_REVIEW,

    /** 회원 관리 */
    MEMBER_MANAGE,

    /** 역할·권한 관리 */
    ROLE_MANAGE;

    /** authrt_cd 컬럼에 저장되는 코드값. enum 이름과 같다 */
    public String code() {
        return name();
    }
}
