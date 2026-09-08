package org.sscc.ssccopsserver.domain.example.controller;

import jakarta.validation.Valid;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.example.dto.ExampleCreateOrUpdateRequest;
import org.sscc.ssccopsserver.domain.example.dto.ExampleReadResponse;
import org.sscc.ssccopsserver.domain.example.service.ExampleService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;

import lombok.RequiredArgsConstructor;

/*
 * controller/service/repository/entity/dto/code 6계층 구조를 보여주는 참고용 예시 도메인.
 * 새 도메인을 추가할 때 이 구조를 복사해서 시작하면 된다.
 *
 * ── 왜 local 프로필에만 등록하는가 (ssccops#244) ────────────────
 * **읽으라고 있는 것이지 부르라고 있는 것이 아니다.** 그런데 이 컨트롤러는 실제로 살아 있었다 —
 * /examples 는 SecurityConfig 의 anyRequest().authenticated() 에만 걸리고 @RequireAuthority 가
 * 없어 **로그인만 하면 누구나 쓰고 지울 수 있었고**, example_entity 는 prod 에 실재하며
 * V1__baseline.sql 에도 들어 있다. "실제 기능 아님"이라는 설명과 달리 배포된 쓰기 경로였다.
 *
 * 권한 코드를 하나 붙이는 쪽은 택하지 않았다 — 이 템플릿에 맞는 권한이 없어 아무거나 붙이면
 * 복사하는 사람이 그것을 규칙으로 읽는다. 파일을 지우는 쪽도 아니다 — 6계층 중 하나가 빠지면
 * 템플릿이 템플릿이 아니게 된다. 그래서 **구조는 그대로 두고 배포에서만 뺀다.**
 *
 * dev 가 아니라 local 인 것은 dev 도 인터넷에서 닿는 배포 환경이기 때문이다(SwaggerConfig 의
 * @Profile("!prod") 와 갈리는 지점 — 그쪽은 dev 에서 열려 있는 것이 목적이다).
 *
 * **이 구조를 복사할 때 이 @Profile 한 줄은 지운다.** 템플릿을 배포에서 빼기 위한 것이지
 * 새 도메인이 따를 규칙이 아니다.
 */
@Profile("local")
@RestController
@RequiredArgsConstructor
@RequestMapping("/examples")
public class ExampleController {

    private final ExampleService exampleService;

    @GetMapping("/{id}")
    public ApiResponse<ExampleReadResponse> read(@PathVariable Long id) {
        return ApiResponse.success(exampleService.read(id));
    }

    @PostMapping
    public ApiResponse<ExampleReadResponse> create(
            @Valid @RequestBody ExampleCreateOrUpdateRequest req) {
        return ApiResponse.created(exampleService.create(req));
    }

    @PutMapping("/{id}")
    public ApiResponse<ExampleReadResponse> update(
            @PathVariable Long id, @Valid @RequestBody ExampleCreateOrUpdateRequest req) {
        return ApiResponse.success(exampleService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteSoft(@PathVariable Long id) {
        exampleService.deleteSoft(id);
        return ApiResponse.successWithNoData();
    }
}
