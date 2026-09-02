package org.sscc.ssccopsserver.domain.academicprogram.service;

import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.academicprogram.code.error.AcademicProgramErrorCode;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.SessionEntity;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.SessionRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

/*
 * "지금 이 회차의 부속 자료(출석·인증사진)를 고칠 수 있는가"의 유일한 구현 (#137).
 *
 * 출석 정정(PATCH .../attendances)과 인증사진 업로드(POST .../file-reference)는 바꾸는 것이
 * 다르지만 **통과해야 하는 문은 완전히 같다** — 활동 존재(404) → 스터디장 본인(403) → 그 활동의
 * 회차(404) → 확정되지 않은 회차(409). 이 순서 자체가 판단이라(소유권을 회차 조회보다 먼저 보는
 * 것은 남의 활동에 회차 번호를 바꿔 가며 부르는 것만으로 몇 번 회차가 있는지 알아낼 수 없게
 * 하기 위해서다 — SessionServiceImpl.submitSession과 같은 근거) 서비스 두 곳에 옮겨 적으면
 * 한쪽만 고쳐진 순서가 다른 쪽에 남는다.
 *
 * 조회(GET .../attendances)는 이 문을 쓰지 않는다 — 팀원도 자기 활동의 출석부를 봐야 하므로
 * 소유권이 걸리지 않고, 확정된 회차라고 못 볼 이유도 없다.
 */
@Component
@RequiredArgsConstructor
public class SessionCorrectionPolicy {

    private final AcademicProgramRepository academicProgramRepository;
    private final SessionRepository sessionRepository;
    private final AcademicProgramOwnershipPolicy academicProgramOwnershipPolicy;

    public SessionEntity requireCorrectable(
            Long academicProgramId, Long sessionId, MemberEntity requester) {
        AcademicProgramEntity academicProgram =
                academicProgramRepository
                        .findById(academicProgramId)
                        .orElseThrow(
                                () ->
                                        new GeneralException(
                                                AcademicProgramErrorCode
                                                        .ACADEMIC_PROGRAM_NOT_FOUND));
        academicProgramOwnershipPolicy.requireLeader(academicProgram, requester);

        SessionEntity session = findSession(academicProgramId, sessionId);
        session.requireCorrectable();
        return session;
    }

    /*
     * 활동 범위로 좁혀 읽는다 — 회차 식별자만 보면 /v1/academic-programs/1/sessions/999가 남의
     * 활동 출석부를 돌려준다(SessionRepository.findByIdAndAcademicProgramId 주석). 없는 회차와
     * 남의 활동 회차는 같은 404다.
     *
     * public인 것은 소유권이 걸리지 않는 조회 경로도 이 좁힘만은 똑같이 써야 하기 때문이다.
     */
    public SessionEntity findSession(Long academicProgramId, Long sessionId) {
        return sessionRepository
                .findByIdAndAcademicProgramId(sessionId, academicProgramId)
                .orElseThrow(
                        () -> new GeneralException(AcademicProgramErrorCode.SESSION_NOT_FOUND));
    }

    /*
     * 조회 경로는 활동 엔티티가 필요 없지만 존재 여부는 확인한다 — 없는 활동에 회차 404를
     * 돌려주면 화면이 "활동이 사라진 것"과 "회차가 없는 것"을 구별하지 못한다
     * (SessionServiceImpl.requireAcademicProgramExists와 같은 태도).
     */
    public void requireAcademicProgramExists(Long academicProgramId) {
        if (!academicProgramRepository.existsById(academicProgramId)) {
            throw new GeneralException(AcademicProgramErrorCode.ACADEMIC_PROGRAM_NOT_FOUND);
        }
    }
}
