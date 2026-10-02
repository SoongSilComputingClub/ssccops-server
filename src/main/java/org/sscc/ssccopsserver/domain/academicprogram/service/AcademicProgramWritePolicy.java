package org.sscc.ssccopsserver.domain.academicprogram.service;

import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.academicprogram.code.error.AcademicProgramErrorCode;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramStatus;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/*
 * "지금 이 활동에 쓸 수 있는가"의 유일한 구현 (#597 · ADR-0057 — 종료는 그 활동의 쓰기를 전부
 * 멈춘다 · #611 · ADR-0058 — 폐지도 같다).
 *
 * 이 도메인의 쓰기 경로는 활동을 **여기서** 얻는다. 회차 제출·재제출(SessionServiceImpl) · 회차
 * 검토(SessionReviewServiceImpl) · 출석 정정·인증사진(SessionCorrectionPolicy를 거쳐) · 모집
 * 선발·일정·문항(AcademicProgramRecruitmentServiceImpl). 경로마다 활동을 찾고 소유권을 보고
 * 상태를 보는 세 줄을 옮겨 적으면 경로가 늘 때 한 곳만 빠진다 — 그래서 셋을 한 호출로 묶고,
 * 호출부는 **어떤 자격인지만** 고른다.
 *
 * 순서가 판단이다: 활동 404 → 자격 403 → 종료·폐지 409 ACADEMIC_PROGRAM_COMPLETED·
 * ACADEMIC_PROGRAM_DISCONTINUED → (호출부의) 그 밖의 409. 자격을 종료보다 먼저 보는 것은 권한 없는 요청자에게 «이 활동은 끝났다»를 알리지 않기
 * 위해서다(SessionServiceImpl.submitSession이 소유권을 계획 항목보다 먼저 보는 것과 같은 근거).
 * 자격이 @RequireAuthority인 경로(회차 검토·모집 선발·일정)는 애스펙트가 핸들러 앞에서 이미
 * 403을 끊었으므로 require로 404와 409만 본다.
 *
 * 막지 않는 것 — 조회 전부 · 공유 링크 발급·회수(내용을 바꾸지 않는다) · 재시작·복원 전이 자체
 * (AcademicProgramServiceImpl.transition은 이 클래스를 지나지 않는다. 지나면 종료·폐지된 활동을
 * 다시 열 길이 없다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AcademicProgramWritePolicy {

    private final AcademicProgramRepository academicProgramRepository;
    private final AcademicProgramOwnershipPolicy academicProgramOwnershipPolicy;

    /** 스터디장/팀장 본인의 쓰기 — 회차 제출·재제출 · 출석 정정 · 인증사진 */
    public AcademicProgramEntity requireLeader(Long academicProgramId, MemberEntity requester) {
        AcademicProgramEntity academicProgram = findAcademicProgram(academicProgramId);
        academicProgramOwnershipPolicy.requireLeader(academicProgram, requester);
        requireAcceptsWrites(academicProgram);
        return academicProgram;
    }

    /** 스터디장/팀장 본인 또는 학술국장의 쓰기 — 모집 폼 문항 */
    public AcademicProgramEntity requireLeaderOrManager(
            Long academicProgramId, MemberEntity requester) {
        AcademicProgramEntity academicProgram = findAcademicProgram(academicProgramId);
        academicProgramOwnershipPolicy.requireLeaderOrManager(academicProgram, requester);
        requireAcceptsWrites(academicProgram);
        return academicProgram;
    }

    /*
     * 자격을 @RequireAuthority가 이미 판정한 쓰기 — 회차 검토 · 모집 선발 · 모집 일정. 여기서
     * 다시 권한을 물으면 같은 판정이 두 층에 생긴다.
     */
    public AcademicProgramEntity require(Long academicProgramId) {
        AcademicProgramEntity academicProgram = findAcademicProgram(academicProgramId);
        requireAcceptsWrites(academicProgram);
        return academicProgram;
    }

    /*
     * 거절 코드는 상태마다 다르다(#611) — 종료는 재시작으로, 폐지는 복원으로 풀리므로 화면이 고를
     * 안내가 다르다. switch 식이라 상태를 더하면 여기서 컴파일이 멈춘다.
     */
    private void requireAcceptsWrites(AcademicProgramEntity academicProgram) {
        AcademicProgramStatus status = academicProgram.getStatus();
        if (status.acceptsWrites()) {
            return;
        }
        log.warn(
                "멈춘 학술 활동에 쓰기 시도. academicProgramId={}, status={}",
                academicProgram.getId(),
                status);
        throw new GeneralException(
                switch (status) {
                    case COMPLETED -> AcademicProgramErrorCode.ACADEMIC_PROGRAM_COMPLETED;
                    case DISCONTINUED -> AcademicProgramErrorCode.ACADEMIC_PROGRAM_DISCONTINUED;
                    case APPROVED, ONGOING ->
                            throw new IllegalStateException(status + "는 쓰기를 받는 상태다");
                });
    }

    private AcademicProgramEntity findAcademicProgram(Long academicProgramId) {
        return academicProgramRepository
                .findById(academicProgramId)
                .orElseThrow(
                        () ->
                                new GeneralException(
                                        AcademicProgramErrorCode.ACADEMIC_PROGRAM_NOT_FOUND));
    }
}
