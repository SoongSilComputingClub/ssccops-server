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

    /** 임원 — 최상위 묶음. 아래 전부를 포함한다 */
    EXECUTIVE,

    /** 운영자 — 업무·폼 운영 묶음 */
    OPERATOR,

    /** 업무·하위 업무 관리 */
    WORK_MANAGE,

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
