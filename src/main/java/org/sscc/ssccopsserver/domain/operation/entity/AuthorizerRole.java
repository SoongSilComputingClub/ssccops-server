package org.sscc.ssccopsserver.domain.operation.entity;

import java.util.Arrays;
import java.util.Optional;

import org.sscc.ssccopsserver.domain.member.code.RolePositionCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/*
 * 하위 업무 유형이 지정하는 승인 주체 역할 (sub_work_type.autzr_role_cd).
 * 이 역할을 가진 사람만 그 유형의 하위 업무를 최종 승인·반려(TR-03·TR-04)할 수 있다.
 *
 * 엔티티는 이 값을 enum이 아니라 String으로 들고 있다. 하위 업무 상세(OPS-009)가 이미
 * 문자열로 내보내고 있어 바꾸면 그 응답까지 함께 흔들리기 때문이며, 기준 코드 검증은
 * 요청 DTO가 이 enum으로 받는 것으로 충분하다 — 목록에 없는 값은 역직렬화 단계에서
 * 걸려 전역 핸들러가 INVALID_CODE_VALUE로 바꾼다.
 *
 * **회원 쪽과 잇는 값은 역할명이 아니라 직위 코드(role.role_pstn_cd)다** (#118).
 * 원래는 role에 코드 컬럼이 없어 한글 역할명(role.role_nm)을 직접 비교했는데, 그 값은
 * NOT NULL도 UNIQUE도 아니고 역할 관리 화면에서 바뀌므로 '총무'를 '재무'로 개명하면
 * 예산지출을 승인할 사람이 사라졌다. 이제 개명은 판정에 아무 영향을 주지 않는다.
 *
 * **접미사 매칭도 함께 사라졌다.** 부서별 직책(홍보국장·행정국장 …)을 걸러 내려고
 * DIRECTOR만 '국장'으로 끝나는 이름을 통과시켰는데, 그 때문에 화면에서 만든 '동아리방국장'
 * 같은 사용자 정의 역할이 의도치 않게 승인권을 얻었다. 지금은 부서별 국장 역할에
 * DIRECTOR 코드를 직접 지정하는 것이 그 자리를 대신한다 — 이름이 아니라 지정이 자격을 준다.
 * ('부회장'이 '회장'으로 끝나므로 접미사로 둘 수 없다는 옛 제약도 함께 무의미해졌다.)
 *
 * 투표 자격(ApprovalAuthorityPolicy)도 같은 코드를 본다. 한쪽만 옮기면 개명 뒤에 승인은
 * 되는데 투표가 안 되는(또는 그 반대) 상태가 된다.
 */
@Getter
@RequiredArgsConstructor
public enum AuthorizerRole {
    PRESIDENT(RolePositionCode.PRESIDENT),
    VICE_PRESIDENT(RolePositionCode.VICE_PRESIDENT),
    TREASURER(RolePositionCode.TREASURER),
    DIRECTOR(RolePositionCode.DIRECTOR);

    /*
     * 이 승인자 역할에 해당하는 회원 역할의 직위 코드.
     *
     * 두 enum의 이름이 지금은 같지만 name()으로 비교하지 않고 이 필드로 잇는다 — 이름이
     * 같다는 것은 우연이고, 승인자가 될 수 없는 직위(STAFF)가 RolePositionCode에는 있어
     * 두 어휘의 범위 자체가 다르기 때문이다.
     */
    private final RolePositionCode positionCode;

    /** 회원이 가진 역할의 직위 코드(role.role_pstn_cd)가 이 승인자 역할에 해당하는지. */
    public boolean matches(RolePositionCode memberRolePositionCode) {
        return positionCode == memberRolePositionCode;
    }

    /*
     * 저장된 코드 문자열을 상수로 되돌린다. 값이 없거나 목록에 없는 코드면 비어 있다 —
     * 승인 정책이 깨진 상태이므로 호출부가 '권한 없음'과 나눠 다룬다.
     */
    public static Optional<AuthorizerRole> from(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(role -> role.name().equals(code)).findFirst();
    }
}
