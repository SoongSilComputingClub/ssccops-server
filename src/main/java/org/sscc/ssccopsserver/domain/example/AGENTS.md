# domain/example

이 도메인 규칙의 정본. 루트 AGENTS.md는 여기를 가리키기만 한다.

## 무엇이 있나

- 6계층 한 벌: `ExampleController` · `ExampleService`/`ExampleServiceImpl` · `ExampleRepository` · `ExampleEntity`(`example_entity`)·`ExampleStatus` · `ExampleCreateOrUpdateRequest`·`ExampleReadResponse` · `ExampleErrorCode`.
- 하지 않는 것: 업무 기능. `ExampleController`는 `@Profile("local")`이라 dev·prod에 라우트가 없다.

## 규칙

- 이 도메인의 규칙은 «새 도메인을 어떻게 시작하는가»라 도메인 사이 규칙이며, 그래서 루트 AGENTS.md «아키텍처» 절의 `domain/example` 항목에 그대로 있다 — 복사해서 시작할 것 · `@Profile` 한 줄은 지울 것 · 엔티티와 테이블은 지우지 않을 것(ssccops#244).
- 여기에 기능을 더하지 않는다. 템플릿이 자라면 복사할 때 지울 것이 는다.

## 다른 도메인과 닿는 곳

- 없다. 있으면 템플릿이 아니다.

## 테스트 함정

- `ExampleController`가 `@Profile("local")`이라 `test` 프로필의 컨텍스트에는 없다 — 이 컨트롤러를 부르는 통합 테스트를 쓰지 않는다.
