package org.sscc.ssccopsserver.domain.operation.entity;

import java.util.Arrays;
import java.util.Optional;

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
 * roleName은 회원 도메인 role 테이블의 한글 역할명(role.role_nm)이다. 두 어휘를 잇는 값이
 * DB에 없어(role에 코드 컬럼이 없다) 여기서 매핑한다 — 스키마 변경 없이 오늘 데이터로 동작한다 (#47).
 *
 * **국장은 정확히 일치하지 않는다.** 실제 조직의 직책은 부서별로 나뉘어 홍보국장·행정국장·
 * 학술국장·기획국장처럼 저장되고, data.sql이 시드하는 '국장'은 그 총칭일 뿐이다. 그래서
 * DIRECTOR만 접미사로 판정한다. 회장·부회장·총무는 하나뿐이라 정확히 일치시킨다 —
 * '부회장'이 '회장'으로 끝나므로 접미사로 두면 부회장이 회장 승인권을 갖게 된다.
 *
 * 이 매핑의 약점은 role_nm이 NOT NULL도 UNIQUE도 아니고 역할 관리 화면에서 바뀔 수 있다는
 * 것이다. '총무'를 '재무'로 바꾸면 예산지출을 승인할 사람이 사라지는데 겉으로는 평범한 403이라
 * 원인을 찾기 어렵다. 그래서 ApprovalAuthorityPolicy가 그 경우를 로그로 구분한다.
 * 근본 대책(role.role_cd 컬럼)은 후속 과제로 뺐다 — 회원 도메인 스키마 변경이라 담당이 다르다.
 */
@Getter
@RequiredArgsConstructor
public enum AuthorizerRole {
    PRESIDENT("회장", false),
    VICE_PRESIDENT("부회장", false),
    TREASURER("총무", false),
    DIRECTOR("국장", true);

    private final String roleName;

    // 부서명이 앞에 붙는 직책인지 (홍보국장·행정국장 …). 그렇다면 접미사로 판정한다
    private final boolean departmental;

    /** 회원이 가진 역할명(role.role_nm)이 이 승인자 역할에 해당하는지. */
    public boolean matches(String memberRoleName) {
        if (memberRoleName == null) {
            return false;
        }
        String name = memberRoleName.strip();
        return departmental ? name.endsWith(roleName) : name.equals(roleName);
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
