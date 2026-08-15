package org.sscc.ssccopsserver.global.security.authorization;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;

/*
 * 이 엔드포인트를 부르려면 어떤 권한이 필요한가 (#9).
 *
 * 코드는 **하려는 일**만 선언하고, 어떤 역할이 그 일을 할 수 있는지는 데이터(role_authrt_rel)가
 * 정한다 (BR-M20). 그래서 애노테이션에 역할 이름이나 role_id가 등장하지 않는다 — 역할은
 * 운영진이 화면에서 만드는 사용자 관리 데이터이고, 코드가 그것을 가리키면 역할이 하나 늘 때마다
 * 배포가 필요해진다. role_id는 IDENTITY라 환경마다 값이 다르고 role_nm은 화면에서 바뀐다.
 *
 * hasRole/@PreAuthorize를 쓰지 않는 것도 같은 이유다. GrantedAuthority로 굳히려면 인증 시점에
 * 모든 요청이 역할·권한을 조회해야 하는데, 권한이 필요 없는 엔드포인트가 훨씬 많다.
 *
 * 클래스에 붙이면 그 컨트롤러의 모든 핸들러에 걸리고, 메서드 애노테이션이 있으면 그쪽이 이긴다 —
 * 핸들러마다 요구 권한이 갈리는 컨트롤러(FormController)와 통째로 같은 컨트롤러
 * (FormResponseController)를 같은 방식으로 적기 위해서다.
 *
 * 판정은 RequireAuthorityAspect가 하며 규칙 자체는 AuthorityPolicy 한 곳에 있다.
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireAuthority {

    /** 요구 권한. authrt_cd와 같은 값이며 상위 권한을 가진 회원도 펼침으로 통과한다 */
    AuthorityCode value();
}
