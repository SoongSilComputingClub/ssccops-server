package org.sscc.ssccopsserver.global.security.resolver;

import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.sscc.ssccopsserver.domain.member.code.error.MemberErrorCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.code.error.CommonErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;
import org.sscc.ssccopsserver.global.security.AuthenticatedUser;

/*
 * @CurrentMember 파라미터를 채운다. 미가입 사용자를 걸러내는 지점이 여기 한 곳뿐이라,
 * 회원이 필요한 엔드포인트가 늘어도 각 컨트롤러가 가입 여부를 검사할 필요가 없다.
 */
@Component
public class CurrentMemberArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentMember.class)
                && MemberEntity.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        // 인증 필터를 지나온 요청이라면 도달하지 않는다. permitAll 경로에 @CurrentMember를
        // 잘못 붙였을 때 NullPointerException 대신 401로 드러나게 하는 방어선이다.
        if (authentication == null
                || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new GeneralException(CommonErrorCode.UNAUTHORIZED);
        }

        if (!user.isSignedUp()) {
            throw new GeneralException(MemberErrorCode.SIGNUP_REQUIRED);
        }

        return user.member();
    }
}
