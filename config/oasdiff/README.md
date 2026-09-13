# oasdiff 심각도 덮어쓰기 (#412 · ssccops#342)

`severity-levels.txt`는 `integrate-dev.yml`의 `api-compat` job이
`oasdiff breaking base.json head.json --fail-on ERR --severity-levels <이 파일>`로 쓴다.
형식은 `<check-id> <err|warn|info>` 한 줄에 하나이며 **주석을 허용하지 않는다**(`#` 줄도
«invalid line»으로 죽는다) — 그래서 이유가 여기 따로 있다.

## 왜 덮어쓰나

springdoc이 만드는 스펙에는 `required`가 없다(응답 record 필드에 `@NotNull`·`@Schema(requiredMode)`를
달지 않는다). 그래서 oasdiff 기본값으로는 **응답 필드 삭제가 `response-optional-property-removed` =
info**로 분류돼 게이트가 아무것도 막지 않는다 — 실제로 `capabilities`를 지운 스펙으로 확인했다.
이 프로젝트의 «깨는 변경»(ssccops#342)은 응답 필드 삭제·이름·타입 변경 · 요청 필드 필수화 ·
엔드포인트·enum 값 삭제다. 웹은 응답 필드를 required로 믿고 읽으므로 optional은 스펙의 사정이지
계약의 사정이 아니다.

| 검사 | 기본 | 여기 | 이유 |
|---|---|---|---|
| `response-optional-property-removed` | info | **err** | 응답 필드 삭제·이름 변경(= 삭제 + 추가)이 이것으로 잡힌다 |
| `response-property-enum-value-removed` | info | **err** | enum 값 삭제 |
| `response-property-enum-value-added` | error | **warn** | 새 상태 코드를 더하는 것은 평소 개발이다. 막으면 «평소 개발에 영향 없음»(채택 조건)이 깨진다. 요약에는 남아 사람이 본다 |

기본값 그대로 err인 것들 — `response-property-type-changed` · `api-path-removed-without-deprecation` ·
`request-property-became-required` · `new-required-request-property` · `request-parameter-enum-value-removed` —
은 적지 않는다. 전체 목록과 기본 심각도는 `oasdiff checks changelog`.

## 통과시키려면

PR에 라벨 **`api-breaking-approved`**를 붙이고 실패한 job을 다시 돌린다(`gh run rerun <id> --failed`) —
job이 라벨을 실행 시점에 읽는다. 라벨의 뜻: 마이너 버전업 + 릴리스 노트 마이그레이션 항목 필수.
