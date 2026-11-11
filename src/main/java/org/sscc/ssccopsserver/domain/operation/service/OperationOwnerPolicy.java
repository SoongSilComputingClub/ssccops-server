package org.sscc.ssccopsserver.domain.operation.service;

import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.service.AuthorityPolicy;
import org.sscc.ssccopsserver.domain.member.service.MemberService;
import org.sscc.ssccopsserver.domain.operation.code.error.OperationErrorCode;
import org.sscc.ssccopsserver.domain.operation.entity.OperationEntity;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

/*
 * "누구를 업무·하위 업무의 담당자로 지정할 수 있는가"의 유일한 구현 (ssccops#628 · #709).
 *
 * 담당자는 활동 회원(탈퇴·제명이 아님)이면서 그 운영 건을 처리할 권한을 오늘 행사할 수 있어야
 * 한다. 업무는 WORK_MANAGE(#101 «업무 담당자는 국장 이상»), 하위 업무는 WORK_READ다 — 하위 업무
 * API가 전부 WORK_READ를 요구하므로(SubWorkController), 그 권한이 없는 회원은 담당자가 돼도 자기
 * 건을 열지 못한다. 리더 역할(스터디장·트랙장·프로젝트장)은 권한을 갖지 못하므로(ADR-0067)
 * 리더 역할만 가진 회원과 역할이 없는 회원은 둘 다 여기서 빠진다.
 *
 * 판정 재료는 둘 다 회원 도메인의 것을 그대로 쓴다 — 탈퇴·제명은 MemberService.findAssignableMember,
 * 권한은 AuthorityPolicy.hasAuthority. 담당자 후보 목록(GET /v1/members/assignable?authority=)이
 * 같은 두 규칙(findAllAssignable · memberIdsWithAuthority)으로 거르므로 «목록에는 있는데 등록하면
 * 거절되는 회원»이 생기지 않는다. 이전에는 목록만 권한으로 걸렀고 등록·수정은 탈퇴·제명만 봐서,
 * API·MCP로는 누구든 업무 담당자가 됐다.
 *
 * ── 왜 역할 이름이 아니라 권한인가 ─────────────────────────────
 * 요구는 «역할이 부여된 사람(리더 셋 제외)»이었다. 역할 이름으로 가르면 WORK_READ가 없는 역할
 * (새로 만든 부서 역할 등)을 가진 회원이 담당자가 되고, 그 회원은 403으로 자기 건을 열지 못한다.
 * 판정이 권한과 역할 이름 두 벌이 되는 것도 ADR-0067이 피한 모양이다. 기본 시드에서 WORK_READ를
 * 가진 역할(국원·국장·회장단·최고관리자)이 곧 리더 셋을 뺀 운영진이라 결과는 요구와 같다.
 *
 * ── 왜 회의 책임자는 여기 없나 ─────────────────────────────────
 * 회의 책임자(MEETING_MANAGE)도 목록만 거르고 서버는 보지 않는 같은 구조지만 ssccops#628의
 * 범위가 아니다. 넣을 때는 Kind에 한 줄을 더하고 MeetingServiceImpl이 이 정책을 부른다.
 */
@Component
@RequiredArgsConstructor
public class OperationOwnerPolicy {

    private final MemberService memberService;
    private final AuthorityPolicy authorityPolicy;

    /** 담당자를 정하는 운영 건의 종류. 종류가 요구 권한과 거절 코드를 정한다 */
    public enum Kind {
        WORK(AuthorityCode.WORK_MANAGE, OperationErrorCode.WORK_OWNER_NOT_ELIGIBLE),
        SUB_WORK(AuthorityCode.WORK_READ, OperationErrorCode.SUB_WORK_OWNER_NOT_ELIGIBLE);

        private final AuthorityCode requiredAuthority;
        private final OperationErrorCode notEligible;

        Kind(AuthorityCode requiredAuthority, OperationErrorCode notEligible) {
            this.requiredAuthority = requiredAuthority;
            this.notEligible = notEligible;
        }
    }

    /*
     * 새로 지정하는 담당자 — 등록, 그리고 수정에서 담당자가 바뀔 때.
     *
     * 활동 회원이 아니면 OWNER_NOT_ACTIVE_MEMBER, 권한이 없으면 종류별 *_OWNER_NOT_ELIGIBLE이다.
     * 순서가 뜻이다: 없는 회원·탈퇴 회원에게 «권한이 없다»고 답하면 고칠 길을 잘못 가리킨다.
     */
    public MemberEntity requireAssignable(Kind kind, Long ownerId) {
        MemberEntity owner =
                memberService
                        .findAssignableMember(ownerId)
                        .orElseThrow(
                                () ->
                                        new GeneralException(
                                                OperationErrorCode.OWNER_NOT_ACTIVE_MEMBER));
        if (!authorityPolicy.hasAuthority(owner.getId(), kind.requiredAuthority)) {
            throw new GeneralException(kind.notEligible);
        }
        return owner;
    }

    /*
     * 수정 — **담당자가 그대로면 판정하지 않는다.**
     *
     * 자격은 «지정»의 규칙이지 «유지»의 규칙이 아니다. 국장 임기가 끝나면 그가 맡았던 건의 담당자는
     * 오늘 기준으로 자격을 잃는데, 그 건의 제목·기간·총평을 고칠 때마다 담당자 교체를 강요하면
     * 완료된 업무의 «누가 맡았나»가 지워진다. 웹 수정 화면도 «현재 담당자는 후보에 없어도 그대로
     * 저장»을 전제로 짜여 있다(isAssignablePick · #435). 탈퇴·제명된 현재 담당자도 같은 이유로
     * 그대로 둔다 — 이전에는 이 경우 400이라 그 건은 담당자를 바꾸기 전까지 고칠 수 없었다.
     *
     * 담당자 비교는 식별자로 한다. personInCharge는 지연 로딩 프록시일 수 있는데, 식별자 접근은
     * 프록시를 초기화하지 않아 쿼리가 들지 않는다.
     */
    public MemberEntity requireAssignableOnUpdate(
            Kind kind, OperationEntity operation, Long ownerId) {
        MemberEntity current = operation.getPersonInCharge();
        if (current != null && current.getId().equals(ownerId)) {
            return current;
        }
        return requireAssignable(kind, ownerId);
    }
}
