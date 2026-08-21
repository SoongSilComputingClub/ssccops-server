package org.sscc.ssccopsserver.domain.member.code;

import java.util.List;
import java.util.Optional;

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

    /*
     * 업무 소프트 삭제(#125). WORK_MANAGE의 자식이라 그 보유자(국장 이상)는 자동으로
     * 포함한다. 다른 하위 업무 쓰기 작업과 달리 SubWorkOwnershipPolicy 같은 소유권 계층을
     * 두지 않는다 — 담당자 본인이라도 이 권한이 없으면 삭제할 수 없다(운영진 전용 삭제,
     * 삭제만 먼저 authrt 트리 하나로 판정하기로 한 결정).
     */
    WORK_DELETE,

    /*
     * 하위 업무 소프트 삭제(#125). 별도의 SUB_WORK_MANAGE 권한이 없어(하위 업무 등록도
     * WORK_MANAGE로 게이트돼 있다) WORK_DELETE와 같은 부모 아래 둔다.
     */
    SUB_WORK_DELETE,

    /** 회의 등록·조회·상태 전이·안건 관리 */
    MEETING_MANAGE,

    /** 회의 조회 전용(#101). MEETING_MANAGE의 자식이라 그 보유자는 자동으로 포함한다 */
    MEETING_READ,

    /** 회의 안건 등록·수정·철회 전용(#101) — 회의 자체의 생성·전이는 MEETING_MANAGE만의 몫이다 */
    MEETING_AGENDA_WRITE,

    /*
     * 회의 소프트 삭제(#125). MEETING_MANAGE의 자식이라 그 보유자는 자동으로 포함한다.
     * WORK_DELETE와 같은 결정으로, 회의 책임자 본인이라도 이 권한이 없으면 삭제할 수 없다.
     */
    MEETING_DELETE,

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
    ROLE_MANAGE,

    /*
     * 하위 업무 찬반 투표 자격 (OPS-015 · #123). 직위 코드(role.role_pstn_cd)로 갈리던 투표
     * 자격을 권한으로 옮긴 것이다 — 자격 대상은 이제 코드가 아니라 역할↔권한 매핑이 정한다.
     * 시드는 회장·부회장·총무·국장·국원 역할에 부여한다.
     */
    APPROVAL_VOTE,

    /*
     * 하위 업무 유형이 승인자로 지정하는 결재 권한 4종 (#123). 유형의 autzr_authrt_cd가 이 중
     * 하나를 가리키고, 그 권한 보유자만 그 유형의 하위 업무를 최종 승인·반려할 수 있다.
     *
     * 직위(AuthorizerRole·RolePositionCode)를 권한으로 옮긴 것이라 이름에 직위가 남아 있지만,
     * 판정 재료는 직위가 아니라 권한이다 — 부서별 국장(홍보국장 …) 역할에는 역할별 권한
     * 화면에서 SUB_WORK_APPROVE_DIRECTOR를 부여한다. 어휘가 코드(enum)인 것은 유형 저장
     * 화면의 선택지이자 저장 검증 기준이기 때문이며(@RequireAuthority가 코드를 가리키는 것과
     * 같은 이유), 누가 그 권한을 갖는지는 여전히 데이터가 정한다.
     */
    SUB_WORK_APPROVE_PRESIDENT,
    SUB_WORK_APPROVE_VICE_PRESIDENT,
    SUB_WORK_APPROVE_TREASURER,
    SUB_WORK_APPROVE_DIRECTOR;

    /*
     * 유형이 승인자로 지정할 수 있는 권한들. 선언 순서가 곧 화면 선택지의 표시 순서다.
     * enum 전체가 아니라 부분집합인 것은, 임의 권한(WORK_MANAGE 등)을 승인자로 지정하는 것을
     * 허용하면 '결재 자격'이라는 어휘가 흐려지고 유형 저장 검증이 사라지기 때문이다.
     */
    private static final List<AuthorityCode> SUB_WORK_APPROVERS =
            List.of(
                    SUB_WORK_APPROVE_PRESIDENT,
                    SUB_WORK_APPROVE_VICE_PRESIDENT,
                    SUB_WORK_APPROVE_TREASURER,
                    SUB_WORK_APPROVE_DIRECTOR);

    /** authrt_cd 컬럼에 저장되는 코드값. enum 이름과 같다 */
    public String code() {
        return name();
    }

    /** 하위 업무 유형이 승인자로 지정할 수 있는 결재 권한 목록 (#123) */
    public static List<AuthorityCode> subWorkApprovers() {
        return SUB_WORK_APPROVERS;
    }

    /*
     * 저장된 승인자 권한 코드를 상수로 되돌린다. 값이 없거나 결재 권한이 아니면 비어 있다 —
     * 승인 정책이 깨진 상태이므로 호출부가 '권한 없음'과 나눠 다룬다 (ApprovalAuthorityPolicy).
     */
    public static Optional<AuthorityCode> fromSubWorkApproverCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return SUB_WORK_APPROVERS.stream()
                .filter(approver -> approver.name().equals(code))
                .findFirst();
    }
}
