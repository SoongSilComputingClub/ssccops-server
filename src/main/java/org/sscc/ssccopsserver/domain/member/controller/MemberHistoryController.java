package org.sscc.ssccopsserver.domain.member.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.dto.MemberChangeHistoryResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberHistorySearchCondition;
import org.sscc.ssccopsserver.domain.member.service.MemberHistoryService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.authorization.RequireAuthority;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 회원 변경 이력 통합 조회 API (#82 · ssccops#24). 경로 버전 /v1을 쓰고 컨텍스트 경로에
 * /api를 두지 않는다 (AP-01).
 *
 * **MemberController에 얹지 않고 컨트롤러를 나눈다.** MemberCodeController가 세운 선례와 같은
 * 배치이며, 이유는 두 가지다. 하나는 다루는 자원이 회원 자체가 아니라 회원의 하위 컬렉션
 * (세 이력 테이블)이라는 것이고, 다른 하나는 MemberController가 이미 가입·목록·상세·수정을
 * 안고 있어 회원 도메인의 모든 변경이 그 파일 하나로 몰린다는 것이다. 경로가
 * /v1/members/{memberId}로 시작하지만 겹치지 않는다 — 저쪽은 /v1/members와
 * /v1/members/{memberId}뿐이고 이쪽은 세그먼트가 하나 더 깊다
 * (MemberRoleAssignmentController와 같은 자리).
 *
 * **요구 권한은 MEMBER_MANAGE다.** 역할 이력이 함께 실리지만 ROLE_MANAGE가 아니다 — 여기서
 * 하는 일은 '누가 무엇을 할 수 있는지를 바꾸는 것'이 아니라 회원 관리 화면이 이미 보여 주는
 * 회원 상세('최근 변경' 3건)를 전부 펼쳐 보는 것이고, 같은 화면의 두 영역이 서로 다른 권한을
 * 요구하면 상세는 열리는데 '더 보기'만 403이 된다.
 */
@RestController
@RequestMapping("/v1/members/{memberId}/histories")
@RequireAuthority(AuthorityCode.MEMBER_MANAGE)
@RequiredArgsConstructor
public class MemberHistoryController {

    private final MemberHistoryService memberHistoryService;

    /*
     * page 봉투를 싣지 않는다 (AP-11). 세 출처를 합치므로 단일 컬럼 커서가 성립하지 않고 한
     * 회원의 이력은 많아야 수십 건이라 페이징을 두지 않기로 했다 — 근거는
     * MemberHistoryServiceImpl 주석에 적어 두었다.
     */
    @Operation(
            summary = "회원 변경 이력 통합 조회",
            description =
                    "등급(mbr_grd_hstry)·상태(mbr_stts_hstry)·역할(mbr_role_rel) 이력을 하나의"
                            + " 타임라인으로 합쳐 발생 시각 역순으로 내린다. type으로 출처를 고를 수"
                            + " 있고(GRADE·STATUS·ROLE, 복수 허용) 생략하면 전부다. 역할은 한 배정이"
                            + " 부여(ROLE_ASSIGNED)와 종료(ROLE_ENDED) 두 줄로 나오며, mbr_role_rel에"
                            + " 변경자 컬럼이 없어 changedBy는 항상 null이다. 회원 정보(이름·연락처·학과)"
                            + " 수정 이력은 쌓이는 테이블이 없어 포함되지 않는다. 이력이 없는 회원은 빈"
                            + " 배열이고, 없는 회원은 404 NOT_FOUND, 알 수 없는 type은 400"
                            + " VALIDATION_FAILED다. 페이징을 두지 않으므로 응답은 배열이다.")
    @GetMapping
    public ApiResponse<List<MemberChangeHistoryResponse>> getHistories(
            @PathVariable Long memberId, @ModelAttribute MemberHistorySearchCondition condition) {

        return ApiResponse.success(
                memberHistoryService.getHistories(memberId, condition.sources()));
    }
}
