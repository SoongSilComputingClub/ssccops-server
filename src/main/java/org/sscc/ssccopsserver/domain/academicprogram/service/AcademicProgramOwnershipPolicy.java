package org.sscc.ssccopsserver.domain.academicprogram.service;

import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.academicprogram.code.error.AcademicProgramErrorCode;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.service.AuthorityPolicy;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/*
 * "이 활동의 스터디장/팀장 본인인가"의 유일한 구현 (#133 — S2(#132, 폐기)가 만들려던 것을 대신
 * 신설한다). SubWorkOwnershipPolicy(#101)와 같은 층이다 — leadrMbrId는 정적 권한 코드가 아니라
 * "이 활동 한정" 관계라 @RequireAuthority가 대신할 수 없다(학술관리_API설계.md §4).
 *
 * prpsrMbrId 비교는 두지 않는다 — 생성 시점부터 leadrMbrId = prpsrMbrId로 고정되므로(#133
 * 재설계) 별도로 비교할 이유가 없다.
 *
 * 이 이슈(#133)에는 이 정책이 걸리는 공개 엔드포인트가 없다(전이 2종은 ACADEMIC_PROGRAM_MANAGE
 * 단일 권한으로만 판정한다, 학술관리_API설계.md §6). S4~S8(회차 기록·출석·인증사진·신청자 조회)
 * 이 이 클래스를 재사용한다.
 *
 * **"소유권 또는 관리권한"도 여기 있다** (#138 · requireLeaderOrManager). 두 자격의 OR은
 * @RequireAuthority로 표현할 수 없다 — 애노테이션은 클래스·메서드 어디에 붙어도 AND로만 걸리고,
 * 관리권한을 컨트롤러에 걸어 두면 스터디장 본인이 자기 활동의 신청자를 보러 올 때 소유권 판정이
 * 돌기도 전에 403으로 끊긴다. 서비스 레이어에서 두 판정을 나란히 두는 것이 유일한 방법이고,
 * 그 자리를 여기 하나로 모아 둔다 — 승인 이력 조회(#139)가 같은 규칙을 쓴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AcademicProgramOwnershipPolicy {

    private final AuthorityPolicy authorityPolicy;

    public void requireLeader(AcademicProgramEntity academicProgram, MemberEntity requester) {
        if (isLeader(academicProgram, requester)) {
            return;
        }

        log.warn(
                "학술 활동 리더 본인 아님. academicProgramId={}, leadrMbrId={}, requesterId={}",
                academicProgram.getId(),
                academicProgram.getLeader() == null ? null : academicProgram.getLeader().getId(),
                requester == null ? null : requester.getId());
        throw new GeneralException(AcademicProgramErrorCode.FORBIDDEN);
    }

    /*
     * 이 활동의 스터디장/팀장 본인이거나 학술국장(ACADEMIC_PROGRAM_MANAGE)인가 (#138).
     *
     * 소유권을 먼저 본다. 그쪽은 이미 읽어 둔 엔티티의 식별자 비교라 조회가 없고, 권한 조회는
     * 스터디장이 아닐 때만 돈다 — 자기 활동을 여는 대다수 요청에 쿼리를 더하지 않는다
     * (ApprovalAuthorityPolicy가 실패 경로에서만 capabilityListOf를 부르는 것과 같은 태도).
     *
     * 거절은 소유권만 볼 때와 같은 403 FORBIDDEN이다. 둘 중 어느 자격이 모자랐는지는 응답으로
     * 나누지 않는다 — 화면이 고를 안내가 하나이고, 나누면 "당신은 이 활동의 리더가 아니다"라는
     * 사실이 권한 없는 요청자에게 그대로 새어 나간다(로그로는 층이 구분된다).
     */
    public void requireLeaderOrManager(
            AcademicProgramEntity academicProgram, MemberEntity requester) {
        if (isLeader(academicProgram, requester)) {
            return;
        }
        if (requester != null
                && authorityPolicy.hasAuthority(
                        requester.getId(), AuthorityCode.ACADEMIC_PROGRAM_MANAGE)) {
            return;
        }

        log.warn(
                "학술 활동 리더도 학술국장도 아님. academicProgramId={}, leadrMbrId={}, requesterId={}",
                academicProgram.getId(),
                academicProgram.getLeader() == null ? null : academicProgram.getLeader().getId(),
                requester == null ? null : requester.getId());
        throw new GeneralException(AcademicProgramErrorCode.FORBIDDEN);
    }

    public boolean isLeader(AcademicProgramEntity academicProgram, MemberEntity requester) {
        if (requester == null) {
            return false;
        }
        MemberEntity leader = academicProgram.getLeader();
        return leader != null && leader.getId().equals(requester.getId());
    }
}
