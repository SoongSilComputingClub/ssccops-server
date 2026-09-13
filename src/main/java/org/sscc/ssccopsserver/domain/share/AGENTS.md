# domain/share

이 도메인 규칙의 정본. 루트 AGENTS.md는 여기를 가리키기만 한다.

## 무엇이 있나

- 엔티티: `ShareLinkEntity`(`shr_lnk`) · 대상 구분 `ShareTargetType`. 컨트롤러는 익명 미리보기 `PublicShareController`(`GET /public/v1/share/{token}`) 하나뿐이다 — 발급·폐기 엔드포인트는 대상을 소유한 도메인의 컨트롤러에 있다.
- 서비스: `ShareLinkService` · 포트 `SharePreviewProvider`(+`SharePreviewProviders` 레지스트리, 결과는 `SharePreview`).
- 하지 않는 것: 인가 · 대상별 분기 · URL 조립 · 만료.

## 규칙

- `domain/share` — 공유 링크(`shr_lnk`). 운영 콘텐츠를 메신저에 붙였을 때 카드가 펼쳐지도록 **토큰이 박힌 URL**을 만든다 (ssccops#200 · ADR-0016).
  - **토큰이 주는 것은 미리보기까지다.** 크롤러는 `GET /public/v1/share/{token}`으로 제목·요약을 받아 카드를 만들지만, 사람이 누르면 종전대로 로그인과 권한 검사를 거쳐야 내용을 본다. 열람까지 여는 안을 기각한 근거는 ADR-0016에 있다 — 요구는 "열지 않고도 무엇인지 안다"였지 "권한 없는 사람도 내용을 본다"가 아니었고, 후자는 `@RequireAuthority`가 지키는 것 밖에 읽기 경로를 하나 만든다.
  - **경로에 대상 식별자가 없는 것이 요점이다.** `/public/v1/sub-works/{id}/meta` 같은 공개 메타 API를 기각한 이유가 식별자가 연속 정수라 1부터 훑으면 업무 제목이 전부 수집되기 때문이다. 토큰은 `SecureRandom` 32바이트(URL-safe Base64 43자)이며 **짧게 줄이지 말 것** — 추측 가능해지는 순간 그 기각 근거가 통째로 사라진다.
  - **만료를 두지 않는다.** 카드는 굳는데 링크만 죽으면 멀쩡해 보이는 카드를 눌렀을 때 404가 된다. 거두는 길은 운영자가 누르는 **공유 중지**(`DELETE /v1/sub-works/{id}/share`) 하나이며, `rvk_dt`가 NULL이면 살아 있다(별도 `rvk_yn`을 두지 않는 것은 같은 사실이 두 벌이 되기 때문이다 — `OperationEntity.deletedAt`과 같은 모양).
  - **발급은 멱등이다.** 살아 있는 링크가 있으면 그것을 돌려준다 — 만료가 없으므로 누를 때마다 만들면 죽지 않는 링크가 쌓이고 화면이 무엇을 보여줄지에 답이 없다. 그래서 새 자원이 생기지 않는 호출이 있어 201이 아니라 200이다.
  - **없는 토큰·폐기된 토큰·대상이 지워진 토큰은 전부 404 `NOT_FOUND`다.** 나누면 어느 토큰이 한때 존재했는지가 드러나 토큰을 무작위로 둔 이유가 절반 무효가 된다.
  - **이 도메인은 대상이 무엇인지 모른다.** 제목·요약을 꺼내는 것은 `SharePreviewProvider` 구현체이고 그것은 **대상을 소유한 도메인**에 있다(하위 업무 → `domain/operation/service/SubWorkSharePreviewProvider`). 여기에 대상별 분기표를 만들면 대상이 늘 때마다 이 패키지에 남의 도메인 이름이 박힌다 — `SystemFormApprovalHook`과 같은 구조이며, 등록 레지스트리(`SharePreviewProviders`)도 겹침을 기동 시점에 터뜨린다.
  - **인가는 여기서 하지 않는다.** "공유할 수 있는가"는 "그 자원을 볼 수 있는가"와 같은 질문이라 발급·폐기 엔드포인트가 `SubWorkController`에 있고 `@RequireAuthority(WORK_READ)`가 붙는다(`WORK_MANAGE`가 아닌 것은 토큰이 주는 것이 미리보기까지라 이미 그 화면을 보는 사람이 아는 것을 넘지 않기 때문이다). `domain/file`이 접근 제어를 올려받지 않은 것과 같은 이유다.
  - **응답은 토큰이고 URL이 아니다.** 링크가 가리키는 곳은 API가 아니라 운영 웹이라, 서버가 주소를 조립하려면 웹의 호스트를 설정으로 들고 있어야 한다 — 이 저장소는 그 종류의 설정에서 두 번 데었다(`R2_PUBLIC_BASE_URL` #208 · `APP_PUBLIC_BASE_URL` #216). 웹이 `{자기 origin}/s/{token}`을 만든다.
  - **대상은 (`trgt_se_cd`, `trgt_id`) 두 값이고 FK가 없다** — `file_rfrnc`(#220)와 같은 판단이며 근거는 `ShareTargetType` 주석에 있다. 대상이 지워진 뒤 남는 행은 미리보기가 빈 Optional을 돌려줘 폐기된 링크와 같은 404가 된다.
  - **`shr_lnk`는 새 테이블이라 `ddl-auto: update`가 자동으로 만든다** — `dev`·`prod` 수동 DDL이 필요 없다. 다만 **데이터사전 등재는 별도**이며 등재할 컬럼 표는 ssccops#200에 있다.

## 다른 도메인과 닿는 곳

- `SharePreviewProvider` 구현체는 운영(`SubWork`·`Work`·`MeetingSharePreviewProvider`) · 행사(`EventSharePreviewProvider`) · 학술(`AcademicProgram`·`SessionSharePreviewProvider`)에 있다. 대상을 늘리면 `ShareTargetType` 한 줄 + 소유 도메인의 구현체 하나이며 이 패키지에 도메인 이름을 적지 않는다.

## 테스트 함정

- `ShareLinkControllerTest`(하위 업무·업무·회의)가 발급 멱등 · 폐기/대상 삭제 뒤 404 · 없는 토큰과 폐기된 토큰의 구별 불가 · 익명 읽기를 본다. 토큰이 `SecureRandom`이라 값 자체를 못 박지 않는다.
- 자세한 판단은 `ShareLinkServiceImpl`·`ShareTargetType` 주석.
