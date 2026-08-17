package org.sscc.ssccopsserver.domain.operation.service;

import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.service.AuthorityPolicy;
import org.sscc.ssccopsserver.domain.operation.code.error.OperationErrorCode;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/*
 * "이 하위 업무를 다룰 수 있는 사람인가"의 유일한 구현 (#101).
 *
 * 권한 인가(#9)·ApprovalAuthorityPolicy(승인자 본인)와 같은 층이면서 또 다른 축이다 —
 * WORK_MANAGE 보유자(국장 이상)는 어떤 하위 업무든 다룰 수 있지만, 그 권한이 없는 회원은
 * 오직 자신이 담당자(oper.pic_id)인 건만 다룰 수 있다. @RequireAuthority는 레코드를 모르므로
 * 컨트롤러는 WORK_READ까지만 걸고, 이 판정은 서비스 레이어에서 상태를 바꾸기 전에 돈다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubWorkOwnershipPolicy {

    private final AuthorityPolicy authorityPolicy;

    public void requireOwnerOrManager(SubWorkEntity subWork, MemberEntity performer) {
        if (isOwnerOrManager(subWork, performer)) {
            return;
        }

        /*
         * 세 층의 거절이 모두 403 FORBIDDEN 하나라 응답만으로는 어디서 막혔는지 알 수 없다
         * (#118). 판정에 쓴 세 값 — 대상 하위 업무 · 담당자 · 요청자 — 을 남겨 "남의 건을
         * 착수하려 했다"(2층)를 "승인자가 아니다"(3층)·"권한이 없다"(1층)와 구분한다.
         *
         * 담당자는 이미 상세 조회가 join fetch로 들고 있는 값이라 이 로그가 조회를 더하지
         * 않으며, 애초에 실패 경로에서만 돈다 (3층 ApprovalAuthorityPolicy와 같은 패턴).
         * 정상 거절이므로 레벨은 warn이다.
         */
        log.warn(
                "하위 업무 담당자·관리 권한 없음. subWorkId={}, picId={}, performerId={}",
                subWork.getId(),
                ownerIdOf(subWork),
                performer == null ? null : performer.getId());
        throw new GeneralException(OperationErrorCode.FORBIDDEN);
    }

    /*
     * 예외 없이 묻는 버전 (ApprovalAuthorityPolicy.canDecide와 같은 짝). 상세 응답의
     * canApprove·canReject가 승인 필요 없는 유형에서는 이 판정을 대신 써야 한다 — 그 유형의
     * 완료·반려는 승인자가 아니라 담당자·WORK_MANAGE 보유자의 몫이기 때문이다(#101). 여기서
     * 판정이 requireOwnerOrManager와 갈리면 버튼은 보이는데 누르면 403이 나는 자리가 생긴다.
     */
    public boolean isOwnerOrManager(SubWorkEntity subWork, MemberEntity performer) {
        if (performer == null) {
            return false;
        }
        /*
         * 담당자 여부를 먼저 본다 — subWork.getOperation().getPersonInCharge()는 상세 조회가
         * 이미 join fetch로 들고 있는 값이라 쿼리가 들지 않는다. hasAuthority는 역할·권한을
         * 다시 조회해야 하므로(capabilitiesOf) 담당자 본인일 때는 그 비용을 아낀다.
         */
        return isOwner(subWork, performer)
                || authorityPolicy.hasAuthority(performer.getId(), AuthorityCode.WORK_MANAGE);
    }

    private boolean isOwner(SubWorkEntity subWork, MemberEntity performer) {
        MemberEntity owner = subWork.getOperation().getPersonInCharge();
        return owner != null && performer != null && owner.getId().equals(performer.getId());
    }

    private static Long ownerIdOf(SubWorkEntity subWork) {
        MemberEntity owner = subWork.getOperation().getPersonInCharge();
        return owner == null ? null : owner.getId();
    }
}
