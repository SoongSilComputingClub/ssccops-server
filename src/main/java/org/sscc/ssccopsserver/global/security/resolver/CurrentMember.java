package org.sscc.ssccopsserver.global.security.resolver;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/*
 * 인증 주체와 연결된 MemberEntity를 컨트롤러 파라미터로 주입받는다.
 *
 * @AuthenticationPrincipal 대신 이 애노테이션을 쓰는 이유는, principal(AuthenticatedUser)에
 * 회원이 없을 수 있기 때문이다. @AuthenticationPrincipal은 타입이 맞지 않으면 조용히 null을
 * 넘겨 NullPointerException으로 번지지만, 이 애노테이션은 미가입 사용자를 403 SIGNUP_REQUIRED로
 * 명확히 끊는다. 따라서 주입된 값은 항상 null이 아니다.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentMember {}
