package org.sscc.ssccopsserver.domain.academicprogram.controller;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramTypeActivationRequest;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramTypeResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramTypeSaveRequest;
import org.sscc.ssccopsserver.domain.academicprogram.service.AcademicProgramTypeService;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.authorization.RequireAuthority;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 학술 활동 유형 코드테이블 API (#130). 학술관리(AcademicProgram) 도메인 신설의 첫 이슈이며,
 * 후속 이슈(S1 이하)가 typeCd 검증에 이 테이블을 참조한다.
 *
 * 인가는 핸들러마다 갈린다 (#9) — 조회는 인증만, 등록·수정·사용 여부 전환은
 * ACADEMIC_PROGRAM_MANAGE다(#130 API 계약). 클래스에 하나로 걸지 않는 이유가 이것이다.
 *
 * 유형을 지우는 엔드포인트는 두지 않는다. 사용 여부 토글(sub_work_type과 동일 패턴)이
 * 물리 삭제를 대신한다 — 되돌릴 길이 없어지면 안 된다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/academic-program-types")
public class AcademicProgramTypeController {

    private final AcademicProgramTypeService academicProgramTypeService;

    @Operation(
            summary = "학술 활동 유형 목록 조회",
            description = "전체 유형 목록을 indctSeqno 순으로 내려준다. 비활성 유형도 포함한다.")
    @GetMapping
    public ApiResponse<List<AcademicProgramTypeResponse>> getAcademicProgramTypes() {
        return ApiResponse.success(academicProgramTypeService.getAcademicProgramTypes());
    }

    @Operation(summary = "학술 활동 유형 등록", description = "새 유형을 만든다. 등록된 유형은 항상 사용 중(useYn=true)이다.")
    @RequireAuthority(AuthorityCode.ACADEMIC_PROGRAM_MANAGE)
    @PostMapping
    public ResponseEntity<ApiResponse<AcademicProgramTypeResponse>> createAcademicProgramType(
            @Valid @RequestBody AcademicProgramTypeSaveRequest request) {
        AcademicProgramTypeResponse response =
                academicProgramTypeService.createAcademicProgramType(request);
        URI location = URI.create("/v1/academic-program-types/" + response.typeCd());
        return ResponseEntity.created(location).body(ApiResponse.created(response));
    }

    /*
     * 부분 수정이 아니라 폼 전체 저장이다. 본문의 typeCd는 무시된다 — 경로의 값이 유일한
     * 식별자다. 사용 여부는 여기서 바뀌지 않는다 — 목록의 토글이 아래 엔드포인트로 따로 바꾼다.
     */
    @Operation(summary = "학술 활동 유형 수정", description = "수정 폼의 값으로 통째로 덮는다. 코드(typeCd)는 바꿀 수 없다.")
    @RequireAuthority(AuthorityCode.ACADEMIC_PROGRAM_MANAGE)
    @PatchMapping("/{typeCd}")
    public ApiResponse<AcademicProgramTypeResponse> updateAcademicProgramType(
            @PathVariable String typeCd,
            @Valid @RequestBody AcademicProgramTypeSaveRequest request) {
        return ApiResponse.success(
                academicProgramTypeService.updateAcademicProgramType(typeCd, request));
    }

    @Operation(
            summary = "학술 활동 유형 사용 여부 전환",
            description = "목록의 '사용' 토글. 비활성 유형은 새로 등록할 활동이 고를 수 없을 뿐, 이미 그 유형으로 등록된 활동은 그대로 남는다.")
    @RequireAuthority(AuthorityCode.ACADEMIC_PROGRAM_MANAGE)
    @PatchMapping("/{typeCd}/activation")
    public ApiResponse<AcademicProgramTypeResponse> changeActivation(
            @PathVariable String typeCd,
            @Valid @RequestBody AcademicProgramTypeActivationRequest request) {
        return ApiResponse.success(academicProgramTypeService.changeActivation(typeCd, request));
    }
}
