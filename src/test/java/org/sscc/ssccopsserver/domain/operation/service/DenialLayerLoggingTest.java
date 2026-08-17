package org.sscc.ssccopsserver.domain.operation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.code.error.MemberErrorCode;
import org.sscc.ssccopsserver.domain.member.dto.MemberRoleResponse;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.service.AuthorityPolicy;
import org.sscc.ssccopsserver.domain.member.service.MemberService;
import org.sscc.ssccopsserver.domain.operation.code.error.OperationErrorCode;
import org.sscc.ssccopsserver.domain.operation.entity.OperationEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkTypeEntity;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;
import org.sscc.ssccopsserver.global.security.AuthenticatedUser;
import org.sscc.ssccopsserver.global.security.authorization.RequireAuthority;
import org.sscc.ssccopsserver.global.security.authorization.RequireAuthorityAspect;
import org.sscc.ssccopsserver.support.LogCapture;

/*
 * 운영 도메인의 거절이 **로그로 층이 구분되는지** (#118 D3).
 *
 * 세 층은 순서대로 걸리고 응답은 셋 다 403 FORBIDDEN 하나다 — 존재·소유 관계를 응답으로
 * 흘리지 않으려는 의도된 설계라 층별로 에러 코드를 나누지 않는다. 그래서 QA 리포트를 쓸 때
 * "국원이 남의 업무를 착수하려다 막힌 것"(2층)과 "승인자가 아닌 사람이 승인하려다 막힌
 * 것"(3층), "무권한자가 승인함에 들어오려다 막힌 것"(1층)을 응답만으로는 구분할 수 없고
 * 서버 로그가 유일한 단서가 된다.
 *
 * 이 테스트가 못 박는 것은 두 가지다.
 *  - 세 층이 각자 다른 로거로 서로 구별되는 문구를 남기고, 판정에 쓴 값(요구 권한 · 담당자 ·
 *    승인자 역할)이 그 안에 들어 있다.
 *  - **통과 경로에는 아무것도 남지 않고 조회도 늘지 않는다.** 1·2층의 로그가 실패 경로에서만
 *    도는지를 mock 호출로 확인한다 — 3층이 roleNamesOf를 실패 경로에서만 부르는 이유와 같다.
 */
class DenialLayerLoggingTest {

    private static final Long MEMBER_ID = 7L;
    private static final Long OWNER_ID = 42L;
    private static final Long SUB_WORK_ID = 100L;
    private static final Long SUB_WORK_TYPE_ID = 5L;

    private AuthorityPolicy authorityPolicy;
    private MemberService memberService;
    private MemberEntity performer;

    @BeforeEach
    void setUp() {
        authorityPolicy = mock(AuthorityPolicy.class);
        memberService = mock(MemberService.class);
        performer = mock(MemberEntity.class);
        given(performer.getId()).willReturn(MEMBER_ID);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /* ── 1층 · @RequireAuthority ─────────────────────────── */

    /*
     * 요구 권한과 요청자가 실제로 가진 권한이 함께 남아야 한다. '권한이 모자란 사람이 눌렀다'와
     * '역할↔권한 매핑이 비어 있다'는 겉으로 같은 403이라 보유 목록이 없으면 갈리지 않는다.
     */
    @Test
    void authorityDenialLogsRequiredAndHeldAuthorities() throws Exception {
        given(authorityPolicy.hasAuthority(MEMBER_ID, AuthorityCode.WORK_MANAGE)).willReturn(false);
        given(authorityPolicy.capabilityListOf(MEMBER_ID)).willReturn(List.of("WORK_READ"));
        authenticate(performer);

        try (LogCapture logs = LogCapture.of(RequireAuthorityAspect.class)) {
            assertThatThrownBy(
                            () ->
                                    new RequireAuthorityAspect(authorityPolicy)
                                            .checkAuthority(joinPointOf("approvalInbox")))
                    .isInstanceOf(GeneralException.class)
                    .hasFieldOrPropertyWithValue("errorCode", MemberErrorCode.AUTHORITY_REQUIRED);

            assertThat(logs.warnMessages())
                    .singleElement()
                    .asString()
                    .contains("권한 부족")
                    .contains("WORK_MANAGE")
                    .contains("WORK_READ")
                    .contains(String.valueOf(MEMBER_ID));
        }
    }

    /** 통과하는 요청에는 로그도 없고 보유 권한 조회도 붙지 않는다 */
    @Test
    void authorityPassLogsNothingAndAddsNoQuery() throws Exception {
        given(authorityPolicy.hasAuthority(MEMBER_ID, AuthorityCode.WORK_MANAGE)).willReturn(true);
        authenticate(performer);

        try (LogCapture logs = LogCapture.of(RequireAuthorityAspect.class)) {
            new RequireAuthorityAspect(authorityPolicy)
                    .checkAuthority(joinPointOf("approvalInbox"));

            assertThat(logs.warnMessages()).isEmpty();
        }
        verify(authorityPolicy, never()).capabilityListOf(MEMBER_ID);
    }

    /*
     * 미인증·미가입은 남기지 않는다 — 로그인하지 않은 채 링크를 여는 것은 정상적인 흐름이라
     * 남기면 실제 권한 거절이 그 사이에 묻힌다.
     */
    @Test
    void unauthenticatedAndNotSignedUpAreNotLogged() throws Exception {
        try (LogCapture logs = LogCapture.of(RequireAuthorityAspect.class)) {
            RequireAuthorityAspect aspect = new RequireAuthorityAspect(authorityPolicy);

            assertThatThrownBy(() -> aspect.checkAuthority(joinPointOf("approvalInbox")))
                    .isInstanceOf(GeneralException.class);

            authenticate(null);
            assertThatThrownBy(() -> aspect.checkAuthority(joinPointOf("approvalInbox")))
                    .isInstanceOf(GeneralException.class)
                    .hasFieldOrPropertyWithValue("errorCode", MemberErrorCode.SIGNUP_REQUIRED);

            assertThat(logs.warnMessages()).isEmpty();
        }
    }

    /* ── 2층 · SubWorkOwnershipPolicy ────────────────────── */

    /*
     * 대상 하위 업무 · 담당자 · 요청자 셋이 다 있어야 "남의 건을 착수하려 했다"를 알아본다.
     * 담당자는 상세 조회가 이미 join fetch로 들고 있는 값이라 이 로그가 조회를 더하지 않는다.
     */
    @Test
    void ownershipDenialLogsSubWorkOwnerAndPerformer() {
        SubWorkEntity subWork = subWorkOwnedBy(OWNER_ID);
        given(authorityPolicy.hasAuthority(MEMBER_ID, AuthorityCode.WORK_MANAGE)).willReturn(false);

        try (LogCapture logs = LogCapture.of(SubWorkOwnershipPolicy.class)) {
            assertThatThrownBy(
                            () ->
                                    new SubWorkOwnershipPolicy(authorityPolicy)
                                            .requireOwnerOrManager(subWork, performer))
                    .isInstanceOf(GeneralException.class)
                    .hasFieldOrPropertyWithValue("errorCode", OperationErrorCode.FORBIDDEN);

            assertThat(logs.warnMessages())
                    .singleElement()
                    .asString()
                    .contains("하위 업무")
                    .contains(String.valueOf(SUB_WORK_ID))
                    .contains(String.valueOf(OWNER_ID))
                    .contains(String.valueOf(MEMBER_ID));
        }
    }

    /** 담당자 본인은 조용히 통과한다 — 로그도 WORK_MANAGE 조회도 없다 */
    @Test
    void ownerPassesWithoutLogOrQuery() {
        SubWorkEntity subWork = subWorkOwnedBy(MEMBER_ID);

        try (LogCapture logs = LogCapture.of(SubWorkOwnershipPolicy.class)) {
            new SubWorkOwnershipPolicy(authorityPolicy).requireOwnerOrManager(subWork, performer);

            assertThat(logs.warnMessages()).isEmpty();
        }
        verify(authorityPolicy, never()).hasAuthority(MEMBER_ID, AuthorityCode.WORK_MANAGE);
    }

    /* ── 세 층이 서로 구별되는가 ─────────────────────────── */

    /*
     * 같은 403이지만 로거와 문구가 갈린다. 2층은 담당자를, 3층은 승인자 역할을 남기므로
     * 리포트를 쓰는 사람이 어느 관문에서 막혔는지 로그만으로 판단할 수 있다.
     */
    @Test
    void threeLayersAreDistinguishableInLogs() {
        SubWorkEntity ownershipTarget = subWorkOwnedBy(OWNER_ID);
        given(authorityPolicy.hasAuthority(MEMBER_ID, AuthorityCode.WORK_MANAGE)).willReturn(false);

        SubWorkEntity approvalTarget = subWorkNeedingApprovalBy("PRESIDENT");
        given(memberService.findCurrentRoles(MEMBER_ID))
                .willReturn(List.of(new MemberRoleResponse(1L, "기획국원", true)));

        try (LogCapture ownership = LogCapture.of(SubWorkOwnershipPolicy.class);
                LogCapture approval = LogCapture.of(ApprovalAuthorityPolicy.class)) {

            assertThatThrownBy(
                            () ->
                                    new SubWorkOwnershipPolicy(authorityPolicy)
                                            .requireOwnerOrManager(ownershipTarget, performer))
                    .isInstanceOf(GeneralException.class);
            assertThatThrownBy(
                            () ->
                                    new ApprovalAuthorityPolicy(memberService)
                                            .requireApprover(approvalTarget, performer))
                    .isInstanceOf(GeneralException.class);

            assertThat(ownership.warnMessages()).singleElement().asString().contains("picId=");
            assertThat(approval.warnMessages()).singleElement().asString().contains("승인자 역할");
        }
    }

    /* ── 헬퍼 ────────────────────────────────────────────── */

    private static void authenticate(MemberEntity member) {
        AuthenticatedUser user =
                new AuthenticatedUser(UUID.randomUUID(), "qa@sscc.org", "QA", "google", member);
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken(user, null));
    }

    private static JoinPoint joinPointOf(String methodName) throws NoSuchMethodException {
        Method method = Handlers.class.getMethod(methodName);
        MethodSignature signature = mock(MethodSignature.class);
        given(signature.getMethod()).willReturn(method);
        given(signature.toShortString()).willReturn("Handlers." + methodName + "()");

        JoinPoint joinPoint = mock(JoinPoint.class);
        given(joinPoint.getSignature()).willReturn((Signature) signature);
        return joinPoint;
    }

    private static SubWorkEntity subWorkOwnedBy(Long ownerId) {
        MemberEntity owner = mock(MemberEntity.class);
        given(owner.getId()).willReturn(ownerId);

        OperationEntity operation = mock(OperationEntity.class);
        given(operation.getPersonInCharge()).willReturn(owner);

        SubWorkEntity subWork = mock(SubWorkEntity.class);
        given(subWork.getId()).willReturn(SUB_WORK_ID);
        given(subWork.getOperation()).willReturn(operation);
        return subWork;
    }

    private static SubWorkEntity subWorkNeedingApprovalBy(String authorizerRoleCode) {
        SubWorkTypeEntity subWorkType = mock(SubWorkTypeEntity.class);
        given(subWorkType.isApprovalNeeded()).willReturn(true);
        given(subWorkType.getAuthorizerRoleCode()).willReturn(authorizerRoleCode);
        given(subWorkType.getId()).willReturn(SUB_WORK_TYPE_ID);

        SubWorkEntity subWork = mock(SubWorkEntity.class);
        given(subWork.getId()).willReturn(SUB_WORK_ID);
        given(subWork.getSubWorkType()).willReturn(subWorkType);
        return subWork;
    }

    /** 애노테이션을 읽을 대상. 실제 컨트롤러를 끌어오지 않고 요구 권한만 흉내 낸다 */
    static class Handlers {

        @RequireAuthority(AuthorityCode.WORK_MANAGE)
        public void approvalInbox() {}
    }
}
