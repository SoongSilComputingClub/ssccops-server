package org.sscc.ssccopsserver.domain.academicprogram.service;

import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.academicprogram.code.error.AcademicProgramErrorCode;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

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
 */
@Slf4j
@Component
public class AcademicProgramOwnershipPolicy {

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

    public boolean isLeader(AcademicProgramEntity academicProgram, MemberEntity requester) {
        if (requester == null) {
            return false;
        }
        MemberEntity leader = academicProgram.getLeader();
        return leader != null && leader.getId().equals(requester.getId());
    }
}
