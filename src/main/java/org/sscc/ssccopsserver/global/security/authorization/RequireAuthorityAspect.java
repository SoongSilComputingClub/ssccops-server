package org.sscc.ssccopsserver.global.security.authorization;

import java.lang.reflect.Method;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.member.code.error.MemberErrorCode;
import org.sscc.ssccopsserver.domain.member.service.AuthorityPolicy;
import org.sscc.ssccopsserver.global.apipayload.code.error.CommonErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;
import org.sscc.ssccopsserver.global.security.AuthenticatedUser;

import lombok.RequiredArgsConstructor;

/*
 * @RequireAuthority가 붙은 핸들러의 인가 판정 (#9).
 *
 * 응답 규약은 @CurrentMember 리졸버와 같은 계단을 쓴다 — 미인증 401, 인증됐으나 미가입 403
 * SIGNUP_REQUIRED, 가입했으나 권한 부족 403 FORBIDDEN이다. **404로 감추지 않는다** (VR-M10):
 * 내부 운영 도구라 자원의 존재를 숨길 이유가 없고, 403이어야 "권한이 없다"가 전달된다.
 *
 * 미인증 401은 사실상 도달하지 않는다. 시큐리티 필터체인이 이미 전 경로에 인증을 요구하므로
 * 여기까지 온 요청은 토큰을 통과한 요청이다 — permitAll 경로에 이 애노테이션을 잘못 붙였을 때
 * NullPointerException 대신 401로 드러나게 하는 방어선이다(리졸버와 같은 자리).
 *
 * 판정 규칙 자체는 여기 없고 AuthorityPolicy가 갖는다. 이 클래스가 아는 것은 "누가 요청했고
 * 무엇이 필요한가"와 "거절을 어떤 응답으로 내리는가"뿐이다.
 *
 * 인증 주체에 실린 MemberEntity는 준영속이라 식별자만 꺼내 쓴다 — 지연 로딩 필드를 건드리면
 * 인증 필터가 트랜잭션 밖이라 터진다.
 */
@Aspect
@Component
@RequiredArgsConstructor
public class RequireAuthorityAspect {

    private final AuthorityPolicy authorityPolicy;

    @Before(
            "@annotation(org.sscc.ssccopsserver.global.security.authorization.RequireAuthority)"
                    + " || @within(org.sscc.ssccopsserver.global.security.authorization"
                    + ".RequireAuthority)")
    public void checkAuthority(JoinPoint joinPoint) {
        RequireAuthority required = findRequirement(joinPoint);
        if (required == null) {
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new GeneralException(CommonErrorCode.UNAUTHORIZED);
        }

        if (!user.isSignedUp()) {
            throw new GeneralException(MemberErrorCode.SIGNUP_REQUIRED);
        }

        if (!authorityPolicy.hasAuthority(user.member().getId(), required.value())) {
            throw new GeneralException(MemberErrorCode.AUTHORITY_REQUIRED);
        }
    }

    /*
     * 메서드 애노테이션이 클래스 애노테이션을 이긴다. 컨트롤러 전체에 기본 요구 권한을 걸어 두고
     * 핸들러 하나만 더 좁은(또는 다른) 권한으로 덮어쓰는 쓰임을 위해서다.
     */
    private static RequireAuthority findRequirement(JoinPoint joinPoint) {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();

        RequireAuthority onMethod =
                AnnotatedElementUtils.findMergedAnnotation(method, RequireAuthority.class);
        if (onMethod != null) {
            return onMethod;
        }
        return AnnotatedElementUtils.findMergedAnnotation(
                method.getDeclaringClass(), RequireAuthority.class);
    }
}
