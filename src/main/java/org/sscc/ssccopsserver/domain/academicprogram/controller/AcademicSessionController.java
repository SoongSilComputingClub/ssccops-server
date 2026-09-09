package org.sscc.ssccopsserver.domain.academicprogram.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionDetailResponse;
import org.sscc.ssccopsserver.domain.academicprogram.service.SessionService;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.share.code.ShareTargetType;
import org.sscc.ssccopsserver.domain.share.dto.ShareLinkResponse;
import org.sscc.ssccopsserver.domain.share.service.ShareLinkService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 회차를 **회차 id 하나로** 읽는 경로 (#316 · ssccops#253).
 *
 * ## 왜 최상위 경로인가
 *
 * 공유 링크가 들고 오는 것은 대상 ID 하나(`shr_lnk.trgt_id` = 회차 id)다. 착지 화면이 사람을
 * lms의 활동 상세로 보내려면 **활동 id가 있어야 하는데**, 지금까지 회차를 읽는 길은
 * `/v1/academic-programs/{academicProgramId}/sessions/{sessionId}` 하나뿐이라 그 활동 id를
 * 이미 알고 있어야 부를 수 있었다 — 모르는 값을 얻으려는 조회가 그 값을 요구했다.
 *
 * 그래서 활동을 경로에서 뺀다. 중첩 경로에 얹지 않고 **최상위 자원으로 둔 것**은 활동 id를
 * 선택 파라미터로 만들면 같은 핸들러가 "좁히는 조회"와 "좁히지 않는 조회" 둘로 갈리기
 * 때문이다 — 그 분기가 곧 활동 검사를 건너뛰는 자리가 된다. `/v1/sub-works`·`/v1/works`·
 * `/v1/meetings`가 이미 자기 PK로 서는 최상위 자원이며, 회차도 자기 PK(`sesn_id`)가 있다.
 *
 * **기존 중첩 경로는 그대로 둔다.** 활동 문맥 안에서 여는 화면들은 남의 활동 회차 번호로
 * 부르는 것이 404여야 하고, 그 검사는 경로가 가리키는 대상을 못 박는 장치다.
 *
 * 클래스 이름이 `AcademicSession...`인 것은 경로 이름을 따른 것이다. 도메인은 회차를 줄곧
 * `Session`으로 부르지만(`SessionService`·`SessionEntity`), API 밖에서 `/v1/sessions`는
 * 로그인 세션으로 읽힌다 — 공유 도메인이 `ACADEMIC_SESSION`으로 부르는 것과 같은 사정이다.
 *
 * ## 왜 `/public/v1`이 아닌가
 *
 * 착지 화면 자체는 익명이지만, **익명에게 필요한 것은 이미 `/public/v1/share/{token}`이
 * 준다**(제목·요약, ADR-0016). 이 경로가 내리는 것은 미리보기가 아니라 회차 상세 전부다 —
 * 진행 내용·공지에 더해 **출석부**(누가 왔는지)와 인증사진 자리까지 실린다. 익명 층에 두는
 * 것은 permitAll을 더하는 것과 같고(AGENTS.md), 그 응답은 익명에게 나가도 되는 것이 아니다.
 *
 * 착지 화면은 로그인한 사람의 브라우저에서 이 경로를 부르면 된다 — 링크를 누른 사람이
 * 이동해야 할 곳이 lms(로그인이 필요한 앱)라, 어차피 인증 없이는 갈 수 없는 주소다.
 *
 * ## 권한
 *
 * `@RequireAuthority`를 걸지 않고 인증만 요구한다 — **중첩 경로의 회차 상세와 같은 판단이다**
 * (`AcademicProgramSessionController`의 조회 둘). 그쪽이 인증만으로 열려 있고 상태로 감추지도
 * 않으므로, 활동으로 좁히지 않는다고 해서 인증된 회원이 볼 수 있는 것이 늘지 않는다. 활동
 * 검사는 인가 경계가 아니라 경로와 대상이 어긋난 조합을 끊는 장치이며, 여기에는 어긋날 조합
 * 자체가 없다.
 *
 * ## 공유도 여기 있다 (#319)
 *
 * 회차 공유 셋은 `ssccops#311`이 중첩 경로에 두었던 것을 옮겨온 것이고, **중첩 경로에는
 * 남기지 않았다.** 웹의 공유 대상 표(`packages/share-meta`)는 `apiPath: (targetId) => string`
 * 하나로 경로를 만들고 토큰이 들고 오는 것은 대상 ID 하나(`shr_lnk.trgt_id` = 회차 id)라,
 * 활동 id를 함께 요구하는 경로는 그 표에서 조립되지 않는다(ssccops-web#335가 막힌 자리다).
 *
 * 시그니처를 넓히는 대신 경로를 옮긴 것은, 넓히는 순간 공유 표가 **대상마다 식별자가 몇 개
 * 필요한지**를 알게 되기 때문이다 — 그것은 "대상을 더할 때 한 줄만 는다"는 성질을 깨고,
 * `SharePreviewProvider`가 피하려던 방향(공유 쪽이 대상 도메인의 모양을 아는 것)과 같다.
 *
 * **두 주소를 다 두지 않은 이유**는 발급·폐기가 두 문으로 들어오면 어느 쪽으로 만든 링크인지가
 * 코드에 드러나지 않기 때문이다. 회차 **조회**의 중첩 경로는 그대로 둔다 — 활동 문맥 안에서
 * 여는 화면들이 쓰고, 남의 활동 회차 번호로 부르는 것이 404여야 한다(#316의 판단).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/academic-sessions")
public class AcademicSessionController {

    private final SessionService sessionService;
    private final ShareLinkService shareLinkService;

    @Operation(
            summary = "회차 상세 조회 (회차 id 단독)",
            description =
                    "활동 id 없이 회차 하나를 읽는다. 응답은 중첩 경로(GET"
                        + " /v1/academic-programs/{academicProgramId}/sessions/{sessionId})와 같은"
                        + " 모양이며 **academicProgramId가 함께 실린다** — 공유 링크의 착지가 그 값으로 lms 주소를 조립한다. 없는"
                        + " 회차는 404 SESSION_NOT_FOUND다. 인증사진(fileReference) 규칙도 중첩 경로와 같다 — 그 활동의"
                        + " 관계자가 아니면 null이다.")
    @GetMapping("/{sessionId}")
    public ApiResponse<SessionDetailResponse> getAcademicSession(
            @PathVariable Long sessionId, @CurrentMember MemberEntity requester) {
        return ApiResponse.success(sessionService.getSessionById(sessionId, requester));
    }

    /* ── 공유 (ssccops#311 · ADR-0016 · 경로는 #319) ────────── */

    /*
     * 회차 공유 링크 발급. 회차 상세 화면의 '공유' 버튼이 부른다.
     *
     * **프로그램과 별개 대상이다**(`ShareTargetType.ACADEMIC_SESSION`) — 프로그램은 모집을,
     * 회차는 그 회차를 뿌리는 단위라 뿌리는 시점도 받는 사람도 다르다. 묶으면 대상 ID가 무엇을
     * 가리키는지가 다시 갈린다.
     *
     * **@RequireAuthority를 걸지 않는 것은 이 컨트롤러의 조회와 같은 이유다** — 자격의 근거가
     * 정적 권한 코드가 아니고, 회차 상세는 인증만 요구한다. *"볼 수 있는 사람이 공유할 수
     * 있다"*(ssccops#306)를 여기 적용한 결과이며, 소유권 정책(스터디장 본인)을 거는 회차 쓰기
     * 둘과 갈리는 것은 **공유가 내용을 바꾸지 않기 때문이다** — 토큰이 주는 것은 이미 볼 수
     * 있는 제목·요약의 미리보기까지다(ADR-0016).
     *
     * 발급은 멱등이라 몇 번을 눌러도 결과가 같고, 새 자원이 만들어지지 않는 호출이 있어
     * 201이 아니라 200이다.
     */
    @Operation(
            summary = "회차 공유 링크 발급",
            description =
                    "발급은 멱등이다 — 살아 있는 링크가 있으면 그것을 돌려주므로 응답은 언제나 200이다."
                            + " 응답은 URL이 아니라 토큰(shrTkn)이며 웹이 `{자기 origin}/s/{token}`을"
                            + " 조립한다. 없는 회차는 404 SESSION_NOT_FOUND다.")
    @PostMapping("/{sessionId}/share")
    public ApiResponse<ShareLinkResponse> issueShareLink(
            @PathVariable Long sessionId, @CurrentMember MemberEntity issuer) {
        // 없는 회차를 여기서 404로 끊는다 — 조회를 먼저 태우지 않으면 존재하지 않는 대상에
        // 토큰이 발급된다(shr_lnk에 FK가 없어 DB가 막아 주지 않는다).
        sessionService.getSessionById(sessionId, issuer);
        return ApiResponse.success(
                shareLinkService.issue(ShareTargetType.ACADEMIC_SESSION, sessionId, issuer));
    }

    /*
     * 현재 공유 상태. 공유한 적이 없거나 폐기했으면 **data가 null인 200**이다 — '공유 중이
     * 아니다'는 오류가 아니라 정상적인 조회 결과다.
     */
    @Operation(
            summary = "회차 공유 상태 조회",
            description = "공유 중이 아니면 404가 아니라 data가 null인 200이다. 없는 회차면 404다.")
    @GetMapping("/{sessionId}/share")
    public ApiResponse<ShareLinkResponse> getShareLink(
            @PathVariable Long sessionId, @CurrentMember MemberEntity requester) {
        sessionService.getSessionById(sessionId, requester);
        return ApiResponse.success(
                shareLinkService
                        .findActive(ShareTargetType.ACADEMIC_SESSION, sessionId)
                        .orElse(null));
    }

    /*
     * 공유 중지. 만료를 두지 않기로 했으므로(ADR-0016) 이것이 링크를 거두는 유일한 길이다.
     * 살아 있는 링크가 없어도 조용히 지나가며 언제나 200이다.
     */
    @Operation(
            summary = "회차 공유 중지",
            description =
                    "폐기하면 그 토큰으로는 미리보기가 404가 된다. 살아 있는 링크가 없어도 200이다 —"
                            + " 결과가 같은데 두 번째 요청만 오류로 만들 이유가 없다.")
    @DeleteMapping("/{sessionId}/share")
    public ApiResponse<Void> revokeShareLink(
            @PathVariable Long sessionId, @CurrentMember MemberEntity requester) {
        sessionService.getSessionById(sessionId, requester);
        shareLinkService.revoke(ShareTargetType.ACADEMIC_SESSION, sessionId);
        return ApiResponse.successWithNoData();
    }
}
