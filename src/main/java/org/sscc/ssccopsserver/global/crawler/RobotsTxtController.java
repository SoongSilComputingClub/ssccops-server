package org.sscc.ssccopsserver.global.crawler;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/*
 * `GET /robots.txt` — 크롤러 규칙 (#541 · ssccops#482). 무엇을 열고 닫는지와 그 근거는
 * {@link RobotsTxt}의 주석에 있다.
 *
 * **익명 경로다.** SecurityConfig가 이 한 경로를 permitAll로 연다 — 서비스 데이터가 아니라
 * 코드 안의 상수라 `/public/v1` 규칙(업무 API 중 익명 접근은 그 접두사뿐)과 갈리지 않는다.
 * 헬스 프로브·Swagger·RFC 9728 메타데이터(ADR-0026)와 같은 부류다.
 *
 * `ApiResponse`로 감싸지 않는다 — 크롤러가 읽는 것은 평문 그 자체다.
 */
@RestController
public class RobotsTxtController {

    @GetMapping(value = RobotsTxt.PATH, produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public String robots() {
        return RobotsTxt.BODY;
    }
}
