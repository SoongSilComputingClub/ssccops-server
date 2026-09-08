package org.sscc.ssccopsserver.domain.academicprogram.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionDetailResponse;
import org.sscc.ssccopsserver.domain.academicprogram.service.SessionService;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
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
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/academic-sessions")
public class AcademicSessionController {

    private final SessionService sessionService;

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
}
