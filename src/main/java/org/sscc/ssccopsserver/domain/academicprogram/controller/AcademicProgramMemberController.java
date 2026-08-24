package org.sscc.ssccopsserver.domain.academicprogram.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramMemberResponse;
import org.sscc.ssccopsserver.domain.academicprogram.service.AcademicProgramRecruitmentService;
import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 팀원 명단 조회 API (#138 · 학술관리_API설계.md §3.7). event_ptcp를 학술관리 컨텍스트에서
 * 다시 내려주는 얇은 프록시이며 새 테이블은 없다(설계 결정 #3).
 *
 * 모집 컨트롤러(AcademicProgramRecruitmentController)와 나누는 것은 인가 레벨이 다르기
 * 때문이다 — 이쪽은 **인증만**이고(팀원 명단은 활동 상세 화면의 일부라 팀원 누구나 본다,
 * §6 매트릭스) 그쪽은 스터디장 소유권 또는 학술국장 권한이 걸린다. 한 컨트롤러에 두면 핸들러가
 * 하나 늘 때 자격을 빠뜨리는 것만으로 신청자 명부가 전원에게 열린다(회차 기록 #135와 회차
 * 검토 #136을 나눈 것과 같은 판단).
 *
 * 인증만이라는 것이 아무 값이나 내려도 된다는 뜻은 아니다 — 응답에서 개인정보를 덜어내는
 * 자리는 AcademicProgramMemberResponse다(행사 명단 DTO를 재사용하지 않는 이유가 그 주석에 있다).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/academic-programs/{academicProgramId}/members")
public class AcademicProgramMemberController {

    private final AcademicProgramRecruitmentService academicProgramRecruitmentService;

    @Operation(
            summary = "학술 활동 팀원 명단 조회",
            description =
                    "ptcpSttsCd를 생략하면 **취소(CANCELLED)를 포함한 전부**다 — 명단은 활동 이력으로"
                            + " 영구 보존하므로 취소된 행도 남는다(행사 참가자 명단과 같은 규칙)."
                            + " 정렬은 등록 순번(식별자 오름차순)이며, isLeader는 명단이 아니라"
                            + " academic_program.leadr_mbr_id와의 비교에서 온다."
                            + " 모집 시작 전이어도 409가 아니라 빈 목록이다 — 아직 아무도 뽑지 않은"
                            + " 활동의 빈 명단은 정상적인 답이다(신청자 조회·선발과 갈리는 지점).")
    @GetMapping
    public ApiResponse<List<AcademicProgramMemberResponse>> getMembers(
            @PathVariable Long academicProgramId,
            @RequestParam(required = false) EventParticipantStatus ptcpSttsCd) {
        return ApiResponse.success(
                academicProgramRecruitmentService.getMembers(academicProgramId, ptcpSttsCd));
    }
}
