package org.sscc.ssccopsserver.domain.event.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.event.dto.MyApplicationResponse;
import org.sscc.ssccopsserver.domain.event.service.MyApplicationService;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 내 신청 현황 API (ssccops#145 · GET /v1/events/my-applications).
 *
 * **운영자용 컨트롤러와 클래스를 나눈 것이 이 파일의 존재 이유다.** EventController와
 * EventParticipationController는 둘 다 클래스 레벨 @RequireAuthority(EVENT_MANAGE)이고, 그
 * 애노테이션을 클래스에 둔 근거는 "핸들러가 하나 늘 때 빠뜨릴 자리를 만들지 않는다"였다(D8).
 * 신청자용 핸들러를 그 안에 넣으면 그 규칙이 바로 깨진다 — 클래스 애노테이션에 막혀 신청자가
 * 자기 신청을 못 보거나, 그것을 풀려고 메서드 애노테이션을 손대는 순간 운영 경로가 열린다.
 *
 * **인증만 요구하고 권한은 요구하지 않는다.** 대상이 언제나 인증 주체 본인이라 권한으로 좁힐
 * 것이 없다 — 신청은 회원이면 누구나 하고(임시회원 포함, ssccops#61), 자기가 낸 것을 보는 데
 * 운영 권한이 필요할 이유가 없다. 거절은 @CurrentMember가 정한 계단 그대로다:
 * 미인증 401 · 미가입 403 SIGNUP_REQUIRED.
 *
 * **경로에 mbrId를 두지 않는다** — 응답 자동 저장(#36)·내 응답 목록(#143)이 세운 규칙이며,
 * 자리를 만들지 않는 것이 남의 신청에 닿는 경로를 막는 방법이다.
 *
 * 리터럴 세그먼트라 EventController의 GET /v1/events/{eventId}보다 먼저 매칭된다
 * (폼의 .../responses/draft·.../responses/mine이 .../responses/{formRspnsId}와 갈리는 것과 같은
 * 자리). 어느 파일을 고칠지 헷갈리기 쉬우니 이 사실을 여기 적어 둔다.
 *
 * **철회 엔드포인트는 여기 없다** — 허용 범위가 미결 결정(SoongSilComputingClub/ssccops#138)에
 * 걸려 있어 이번 범위에서 제외했다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/events/my-applications")
public class MyApplicationController {

    private final MyApplicationService myApplicationService;

    @Operation(
            summary = "내 신청 현황 조회",
            description =
                    "인증 주체 본인이 행사에 낸 신청 전부. 인증만 필요하고 별도 권한은 요구하지 않는다."
                            + " applicationStatus는 참가자 명단과 폼 응답에서 파생한 **단일 상태**이며"
                            + " (SUBMITTED·ACCEPTED·REJECTED·CONFIRMED·WAITLISTED·CANCELLED),"
                            + " 명단(event_ptcp) 행이 있으면 그 상태가 응답 상태를 이긴다 —"
                            + " 참가자 등록이 심사보다 뒤에 일어나기 때문이다."
                            + " 작성 중(DRAFT) 응답은 신청이 아니므로 제외한다."
                            + " **대기 순번은 싣지 않는다**(D5 — 신청자에게 비공개)."
                            + " 행사가 보관(ARCHIVED)·작성 중(DRAFT)이어도 본인 신청 이력은 보인다"
                            + " — 공개 목록 노출과는 다른 문제다."
                            + " 정렬은 제출 일시 내림차순(동률은 formRspnsId 내림차순)이고,"
                            + " 신청이 없으면 빈 배열이다.")
    @GetMapping
    public ApiResponse<List<MyApplicationResponse>> getMyApplications(
            @CurrentMember MemberEntity member) {
        return ApiResponse.success(myApplicationService.getMyApplications(member));
    }
}
