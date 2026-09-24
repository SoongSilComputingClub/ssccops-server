package org.sscc.ssccopsserver.domain.academicprogram.service;

import java.util.List;

import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramCondition;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramDetailResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramSearchResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramTransitionRequest;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramTransitionResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.CurriculumItemWithSessionResponse;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/*
 * 학술 활동(스터디/프로젝트) 조회(#131) + 국장 전용 상태 전이(#133). 등록은 폼 응답 승인 이관
 * (ssccops#148)이 맡으므로 여기에는 없다(2026-08-23 설계 변경) — AcademicProgram 행이 생기는
 * 자리가 이 서비스 밖으로 옮겨졌을 뿐, 조회 계약(isLeader/isProposer 판정 포함)은 그대로다.
 */
public interface AcademicProgramService {

    AcademicProgramDetailResponse getAcademicProgram(Long academicProgramId, MemberEntity viewer);

    AcademicProgramSearchResponse searchAcademicPrograms(
            AcademicProgramCondition condition, MemberEntity viewer);

    /*
     * 국장 전용 2액션(#133 · POST /v1/academic-programs/{id}/transitions). 전이표·사전 검증은
     * AcademicProgramTransition·AcademicProgramEntity.changeStatus가 갖고, 여기서는 START_
     * RECRUITMENT의 폼 오케스트레이션(기간 반영 → OPEN 전이)과 APPROVE_COMPLETION의 승인
     * 이력 기록만 더한다.
     */
    AcademicProgramTransitionResponse transition(
            Long academicProgramId,
            AcademicProgramTransitionRequest request,
            MemberEntity performer);

    /*
     * 계획 + 실적 조인 조회(#134 · GET /v1/academic-programs/{id}/curriculum-items). 인증만
     * 요구하지만 isEditable 판정에 요청자 본인 식별이 필요해 viewer를 받는다(상세 조회의
     * isLeader와 같은 이유).
     */
    List<CurriculumItemWithSessionResponse> getCurriculumItems(
            Long academicProgramId, MemberEntity viewer);

    /*
     * 공유 링크 **폐기** 자격 (#556 · ssccops#501).
     *
     * 발급은 «인증만»이 맞다 — «볼 수 있는 사람이 공유할 수 있다»(토큰이 주는 것은 미리보기까지)
     * 이고 `ACADEMIC_PROGRAM_MANAGE`를 걸면 정작 뿌릴 스터디장이 막힌다(컨트롤러 주석).
     * **그 논증은 한 줄도 폐기를 다루지 않는데** 같은 게이트가 폐기에도 걸려 있었다.
     *
     * 결과로 보면 둘은 반대다. 발급은 멱등이고 되돌릴 수 있지만, 폐기는 **이미 퍼진 주소를
     * 죽인다** — 다시 발급해도 멱등이 아니라 새 토큰이라(살아 있는 링크가 없으니 새로 만든다)
     * 단톡방에 뿌린 모집 링크는 살아나지 않는다. 그리고 `revoke`는 살아 있는 링크가 없어도
     * 200이라 성공과 무의미한 호출이 구별되지 않았다.
     *
     * 하위 업무 쪽은 최소한 `WORK_READ`라는 **부여된 권한**을 요구한다. 학술만 그 층이 없었다.
     *
     * 리더 **또는** 학술국장인 것은 기존 `requireLeaderOrManager`를 그대로 쓴 것이다 —
     * 뿌린 사람이 거두고, 국장은 잘못 뿌려진 것을 거둘 수 있어야 한다.
     */
    void requireShareRevocable(Long academicProgramId, MemberEntity requester);
}
