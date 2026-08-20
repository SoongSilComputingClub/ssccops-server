package org.sscc.ssccopsserver.domain.operation.service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.service.AuthorityPolicy;
import org.sscc.ssccopsserver.domain.operation.code.error.OperationErrorCode;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkTypeEntity;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/*
 * "누가 승인·반려할 수 있고 누가 투표할 수 있는가"의 유일한 구현 (#47).
 *
 * 권한 인가(#9)와 층이 다르다. 그쪽은 "이 사람이 업무를 다룰 수 있는가"(WORK_MANAGE)를
 * 애노테이션으로 보고, 여기는 "이 건의 승인자 본인인가"를 본다 — 유형마다 답이 달라지므로
 * @RequireAuthority 하나로 표현되지 않는다. 판정을 한 곳에 모아 두지 않으면 승인함이 그리는
 * 버튼과 실제 전이가 어긋난다.
 *
 * **판정 재료는 직위 코드(role.role_pstn_cd)가 아니라 권한이다** (#123). 승인 자격은 유형이
 * 지정한 결재 권한(sub_work_type.autzr_authrt_cd), 투표 자격은 APPROVAL_VOTE 권한이며, 둘 다
 * AuthorityPolicy의 펼침(회원 → 유효 역할 → 권한 → 자손)으로 판정한다. 규칙을 여기서 한 벌
 * 더 적지 않는 것(BR-M28)은 물론이고, 자격이 세션 capabilities에 그대로 실리므로 화면이
 * 투표 버튼을 서버 판정 없이 그릴 수 있다. 누가 자격을 갖는지는 역할↔권한 매핑(운영 데이터)이
 * 정한다 — 직위 코드 시절처럼 자격 대상을 바꾸는 데 배포가 필요하지 않다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalAuthorityPolicy {

    private final AuthorityPolicy authorityPolicy;

    /*
     * 최종 승인·반려(TR-03·TR-04)를 할 수 있는 사람인지. 유형이 지정한 결재 권한
     * (autzr_authrt_cd) 보유자만 통과한다.
     *
     * 승인이 필요 없는 유형은 검사하지 않는다 — 승인 단계 자체가 없어 승인자도 없으므로,
     * 여기서 막으면 저위험 업무(REQ-016)를 아무도 완료할 수 없게 된다. 그 유형의 완료는
     * 담당자의 몫이고, 애초에 업무를 다룰 수 있는지는 WORK_MANAGE 권한(#9)이 이미 걸렀다.
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
        Optional<AuthorityCode> required =
                AuthorityCode.fromSubWorkApproverCode(subWorkType.getAuthorizerAuthorityCode());
        if (required.isEmpty()) {
            /*
             * 승인이 필요한데 승인자 권한이 비었거나 결재 권한 목록에 없는 값이다. 유형 저장
             * (OPS-019)이 막는 조합이라 정상 경로로는 생기지 않는다 — 데이터가 깨진 것이다.
             */
            log.error(
                    "승인자 권한 코드가 유효하지 않다. subWorkId={}, subWorkTypeId={}, autzrAuthrtCd={}",
                    subWork.getId(),
                    subWorkType.getId(),
                    subWorkType.getAuthorizerAuthorityCode());
        } else {
            /*
             * 판정에 쓴 양쪽 값을 남긴다. 결재 권한을 어느 역할에도 매핑하지 않으면 여전히
             * 승인자가 없는 것과 같기 때문이다 — 그 경우가 '보유=[]'로 드러난다.
             */
            log.warn(
                    "승인자 권한 불일치. subWorkId={}, 필요={}, 보유={}",
                    subWork.getId(),
                    required.get().code(),
                    heldApproverCodesOf(performer));
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
        return canDecide(subWork, authorityPolicy.capabilitiesOf(member.getId()));
    }

    /*
     * 목록의 카드마다 같은 판정을 묻기 위한 형태 (승인함 OPS-017의 canApprove·canReject · #62).
     *
     * 권한은 회원의 것이지 하위 업무의 것이 아니므로 **한 번만 읽어 재사용한다** — 카드마다
     * canDecide를 부르면 판정 자체는 맞지만 권한 조회가 카드 수만큼 따라붙는다 (DB-13).
     * 규칙은 아래 canDecide 한 곳에만 있어 상세(#58)·전이 검사와 갈리지 않는다.
     */
    public Predicate<SubWorkEntity> decidableBy(MemberEntity member) {
        Set<String> capabilities =
                member == null ? Set.of() : authorityPolicy.capabilitiesOf(member.getId());
        return subWork -> canDecide(subWork, capabilities);
    }

    private boolean canDecide(SubWorkEntity subWork, Set<String> capabilities) {
        SubWorkTypeEntity subWorkType = subWork.getSubWorkType();
        if (!subWorkType.isApprovalNeeded()) {
            return true;
        }
        return AuthorityCode.fromSubWorkApproverCode(subWorkType.getAuthorizerAuthorityCode())
                .filter(required -> capabilities.contains(required.code()))
                .isPresent();
    }

    /*
     * 찬반 투표(OPS-015)를 할 수 있는 사람인지 — APPROVAL_VOTE 권한 보유자다. '운영진 누구나'
     * 라는 정의서 규칙은 시드가 회장·부회장·총무·국장·국원 역할에 이 권한을 부여하는 것으로
     * 데이터화됐고(#123), 대상을 넓히고 좁히는 것은 이제 역할별 권한 화면의 몫이다.
     *
     * 승인자 본인과 등록자 본인도 막지 않는다. "운영진은 누구나"라는 규칙에 예외를 두지
     * 않았고, 자가 승인도 차단이 아니라 표시로 다루는 것이 현재 정책이기 때문이다(POL-006).
     */
    public void requireStaff(MemberEntity member) {
        if (member == null
                || !authorityPolicy.hasAuthority(member.getId(), AuthorityCode.APPROVAL_VOTE)) {
            throw new GeneralException(OperationErrorCode.FORBIDDEN);
        }
    }

    /*
     * 회원이 보유한 결재 권한만 추린다 — 거절 로그의 '보유' 자리에 회원의 권한 전부를 찍으면
     * 판정과 무관한 코드가 섞여 원인을 읽기 어렵다. 실패 경로에서만 돈다.
     */
    private List<String> heldApproverCodesOf(MemberEntity member) {
        Set<String> capabilities = authorityPolicy.capabilitiesOf(member.getId());
        return AuthorityCode.subWorkApprovers().stream()
                .map(AuthorityCode::code)
                .filter(capabilities::contains)
                .toList();
    }
}
