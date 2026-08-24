package org.sscc.ssccopsserver.domain.event.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.event.dto.PublicEventDetailResponse;
import org.sscc.ssccopsserver.domain.event.dto.PublicEventSummaryResponse;
import org.sscc.ssccopsserver.domain.event.service.PublicEventService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 공개 행사 조회 API (ssccops#143 · D1). **서버에서 익명 접근이 허용되는 유일한 업무 API 층이다.**
 *
 * 경로 접두사가 /v1이 아니라 /public/v1인 것이 이 컨트롤러의 핵심이다. 지금까지 permitAll은
 * 배포 헬스 프로브와 비prod Swagger뿐이었고 나머지는 전부 인증을 요구했다 — 폼의 '공개'
 * 경로(PublicFormController)조차 "누구나 링크를 열 수 있다"는 뜻이지 익명 접근이 아니었다.
 * 행사 공개 조회는 가입 전 지원자·외부인이 행사를 접하고 신청으로 이어지는 것이 목적이라(D1)
 * 로그인을 요구하는 순간 그 경로가 닫힌다.
 *
 * 그 예외를 **경로로** 표현한다. /v1/** 아래에 익명 엔드포인트를 섞으면 그때부터 "이 경로가
 * permitAll인가"를 엔드포인트별 목록으로 관리해야 하고, 한 줄이 빠지는 것으로 인증이 필요한
 * 자원이 열린다. 접두사로 갈라 두면 SecurityConfig의 규칙이 한 줄로 끝나고, 공개 엔드포인트가
 * 늘어도 시큐리티 설정을 다시 손대지 않는다. **여기에 새 핸들러를 더할 때는 그 응답이 익명에게
 * 나가도 되는지가 유일한 질문이다.**
 *
 * @RequireAuthority도, @CurrentMember도 없다 — 요청 주체가 없는 것이 정상이므로 주체를 받는
 * 자리를 만들지 않는다.
 *
 * 남용 방어(rate limit·캐싱)는 이 층이 아니라 Cloudflare Workers 레벨의 결정을 따른다
 * (SoongSilComputingClub/ssccops#137 미결). 서버에 인메모리 제한기를 먼저 넣으면 인스턴스별로
 * 갈리는 장치가 남는다 (MemberLinkAttemptLimiter가 계정당 판정이라 감수한 한계와 달리, 여기는
 * 주체가 없어 IP당이 되고 그 값은 인스턴스마다 다르게 세어진다).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/public/v1/events")
public class PublicEventController {

    private final PublicEventService publicEventService;

    /*
     * 공개 행사 목록. 상태 필터가 없는 것이 계약이다 — 나오는 것은 언제나 PUBLISHED뿐이다.
     */
    @Operation(
            summary = "공개 행사 목록 조회(익명)",
            description =
                    "공개 앱의 행사 목록. **인증이 필요 없다.** 게시(PUBLISHED)된 행사만 내려오며"
                            + " 작성 중(DRAFT)·보관(ARCHIVED) 행사는 존재 자체를 노출하지 않는다."
                            + " eventClsfCd는 선택 필터다. eventPhase는 행사 일시에서 조회 시점에"
                            + " 파생한 값이고, receiptStatus는 연결된 폼의 접수 상태다(폼이 없으면 null)."
                            + " 목록에는 본문(mtxtCn)을 싣지 않으며 작성자·참가자 정보도 싣지 않는다.")
    @GetMapping
    public ApiResponse<List<PublicEventSummaryResponse>> getPublishedEvents(
            @RequestParam(required = false) String eventClsfCd) {
        return ApiResponse.success(publicEventService.getPublishedEvents(eventClsfCd));
    }

    @Operation(
            summary = "공개 행사 상세 조회(익명)",
            description =
                    "공개 앱의 행사 상세. **인증이 필요 없다.** 본문(mtxtCn)은 md 원문 그대로이며"
                            + " 렌더링·sanitize는 공개 앱의 안전 렌더러 책임이다(D12)."
                            + " 참가 인원은 확정 인원(confirmedCount)과 정원(ptcpLmtCnt) 숫자까지만"
                            + " 내려주고 명단은 공개하지 않는다."
                            + " 게시되지 않은 행사(DRAFT·ARCHIVED)와 없는 행사는 모두"
                            + " 404 EVENT_NOT_FOUND다 — 존재를 감춘다.")
    @GetMapping("/{eventId}")
    public ApiResponse<PublicEventDetailResponse> getPublishedEvent(@PathVariable Long eventId) {
        return ApiResponse.success(publicEventService.getPublishedEvent(eventId));
    }
}
