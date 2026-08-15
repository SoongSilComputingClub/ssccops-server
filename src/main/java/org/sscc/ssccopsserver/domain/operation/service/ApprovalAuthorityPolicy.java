package org.sscc.ssccopsserver.domain.operation.service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.member.dto.MemberRoleResponse;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.service.MemberService;
import org.sscc.ssccopsserver.domain.operation.code.error.OperationErrorCode;
import org.sscc.ssccopsserver.domain.operation.entity.AuthorizerRole;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkTypeEntity;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/*
 * "누가 승인·반려할 수 있고 누가 투표할 수 있는가"의 유일한 구현 (#47).
 *
 * 판정을 서비스 한 곳에 모아 두는 것은 역할 기반 인가(#9)가 AOP로 붙을 때 통째로 옮기기
 * 위해서다. 두 곳에 복제되면 승인함이 그리는 버튼과 실제 전이가 어긋난다.
 *
 * 회원의 역할은 회원 도메인 Service를 경유해서만 읽는다 (AR-07·LY-10) — 다른 도메인의
 * Repository를 직접 주입하지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalAuthorityPolicy {

    /*
     * 투표할 수 있는 운영진 판정 (#47). 정의서 OPS-015의 '회장단'보다 넓다 — 이슈 본문이
     * "회장/부회장/총무/국장/국원 등 사전에 운영진 권한을 가진 운영자 누구나"라고 못박고 있다.
     *
     * 실제 직책은 부서별로 나뉜다: 회장 · 부회장 · 총무 · 홍보국장 · 행정국장 · 학술국장 ·
     * 기획국장 · 홍보국원 · 행정국원 · 학술국원 · 기획국원. 부서가 늘 때마다 이 목록을 고치지
     * 않도록 국장·국원은 **접미사**로 판정한다(data.sql이 시드하는 총칭 '국장'·'국원'도 함께 걸린다).
     *
     * 프로젝트장·스터디장은 빠진다 — 활동 단위의 장이지 운영 의사결정 주체가 아니다.
     * role.indct_seqno(서열 순번)로 "n위 이내"를 판정하지 않는 것은, 그 순번이 화면 정렬용이라
     * 역할이 하나 끼어들면 기준이 조용히 바뀌기 때문이다.
     */
    private static final Set<String> STAFF_ROLE_NAMES = Set.of("회장", "부회장", "총무");

    private static final Set<String> STAFF_ROLE_SUFFIXES = Set.of("국장", "국원");

    private final MemberService memberService;

    /*
     * 최종 승인·반려(TR-03·TR-04)를 할 수 있는 사람인지. 유형이 지정한 승인자 역할
     * (autzr_role_cd) 보유자만 통과한다.
     *
     * 승인이 필요 없는 유형은 검사하지 않는다 — 승인 단계 자체가 없어 승인자도 없으므로,
     * 여기서 막으면 저위험 업무(REQ-016)를 아무도 완료할 수 없게 된다. 그 유형의 완료 권한은
     * 담당자·국장 이상이며 그 통제는 역할 인가(#9)의 몫이다.
     */
    public void requireApprover(SubWorkEntity subWork, MemberEntity performer) {
        if (canDecide(subWork, performer)) {
            return;
        }

        /*
         * 막힌 이유를 둘로 나눠 남긴다. 둘 다 겉으로는 평범한 403이라 로그가 없으면
         * '권한이 없는 사람이 눌렀다'와 '승인 정책 데이터가 깨졌다'를 구분할 수 없다.
         * 이 조회는 실패 경로에서만 돈다 — 통과하는 요청에 쿼리를 더하지 않는다.
         */
        SubWorkTypeEntity subWorkType = subWork.getSubWorkType();
        Optional<AuthorizerRole> required =
                AuthorizerRole.from(subWorkType.getAuthorizerRoleCode());
        if (required.isEmpty()) {
            /*
             * 승인이 필요한데 승인자 역할이 비었거나 기준 코드에 없는 값이다. 유형 저장
             * (OPS-019)이 막는 조합이라 정상 경로로는 생기지 않는다 — 데이터가 깨진 것이다.
             */
            log.error(
                    "승인자 역할 코드가 유효하지 않다. subWorkId={}, subWorkTypeId={}, autzrRoleCd={}",
                    subWork.getId(),
                    subWorkType.getId(),
                    subWorkType.getAuthorizerRoleCode());
        } else {
            /*
             * 역할 관리 화면에서 역할명을 바꾸면(role.role_nm은 NOT NULL도 UNIQUE도 아니다)
             * 승인할 수 있는 사람이 사라진다. 판정에 쓴 양쪽 값을 남겨 매핑이 깨진 경우를
             * 로그에서 알아볼 수 있게 한다.
             */
            log.warn(
                    "승인자 역할 불일치. subWorkId={}, 필요={}, 보유={}",
                    subWork.getId(),
                    required.get().getRoleName(),
                    roleNamesOf(performer));
        }
        throw new GeneralException(OperationErrorCode.FORBIDDEN);
    }

    /*
     * 같은 판정을 예외 없이 묻는다 (OPS-009의 canApprove·canReject · #58). 상세 화면이 승인·반려
     * 버튼을 그릴지 정하는 데 쓰므로 흐름을 끊을 수 없다.
     *
     * requireApprover와 규칙을 공유하는 것이 핵심이다 — 판정이 두 벌이 되면 버튼은 보이는데
     * 누르면 403이 나거나 그 반대가 된다. 여기서 로그를 남기지 않는 것은 조회마다 찍혀
     * 실제 거절과 섞이기 때문이다.
     */
    public boolean canDecide(SubWorkEntity subWork, MemberEntity member) {
        if (!subWork.getSubWorkType().isApprovalNeeded()) {
            // 승인 단계가 없는 유형은 승인자도 없다. 막으면 저위험 업무를 아무도 완료할 수 없다
            return true;
        }
        if (member == null) {
            return false;
        }
        return canDecide(subWork, roleNamesOf(member));
    }

    /*
     * 목록의 카드마다 같은 판정을 묻기 위한 형태 (승인함 OPS-017의 canApprove·canReject · #62).
     *
     * 역할은 회원의 것이지 하위 업무의 것이 아니므로 **한 번만 읽어 재사용한다** — 카드마다
     * canDecide를 부르면 판정 자체는 맞지만 회원 역할 조회가 카드 수만큼 따라붙는다 (DB-13).
     * 규칙은 아래 canDecide 한 곳에만 있어 상세(#58)·전이 검사와 갈리지 않는다.
     */
    public Predicate<SubWorkEntity> decidableBy(MemberEntity member) {
        List<String> roleNames = member == null ? List.of() : roleNamesOf(member);
        return subWork -> canDecide(subWork, roleNames);
    }

    private boolean canDecide(SubWorkEntity subWork, List<String> roleNames) {
        SubWorkTypeEntity subWorkType = subWork.getSubWorkType();
        if (!subWorkType.isApprovalNeeded()) {
            return true;
        }
        return AuthorizerRole.from(subWorkType.getAuthorizerRoleCode())
                .filter(required -> roleNames.stream().anyMatch(required::matches))
                .isPresent();
    }

    /*
     * 찬반 투표(OPS-015)를 할 수 있는 사람인지. 사전에 운영진 권한을 가진 회원이면 누구나
     * 통과한다 — 승인자만의 권한이 아니다.
     *
     * 승인자 본인과 등록자 본인도 막지 않는다. "운영진은 누구나"라는 규칙에 예외를 두지
     * 않았고, 자가 승인도 차단이 아니라 표시로 다루는 것이 현재 정책이기 때문이다(POL-006).
     */
    public void requireStaff(MemberEntity member) {
        if (roleNamesOf(member).stream().noneMatch(ApprovalAuthorityPolicy::isStaffRole)) {
            throw new GeneralException(OperationErrorCode.FORBIDDEN);
        }
    }

    private static boolean isStaffRole(String roleName) {
        if (roleName == null) {
            return false;
        }
        String name = roleName.strip();
        return STAFF_ROLE_NAMES.contains(name)
                || STAFF_ROLE_SUFFIXES.stream().anyMatch(name::endsWith);
    }

    private List<String> roleNamesOf(MemberEntity member) {
        return memberService.findCurrentRoles(member.getId()).stream()
                .map(MemberRoleResponse::roleName)
                .toList();
    }
}
