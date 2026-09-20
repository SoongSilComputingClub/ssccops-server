# domain/content

이 도메인 규칙의 정본. 루트 AGENTS.md는 여기를 가리키기만 한다.

## 무엇이 있나

- 엔티티: `ContentPageEntity`(`cntnt_page`) · `ContentPageHistoryEntity`(`cntnt_page_hstry`) · `ContentPostEntity`(`cntnt_post`) · `ContentPostHistoryEntity`(`cntnt_post_hstry`). 전이표(DRAFT ↔ PUBLISHED)는 엔티티의 `publish`/`unpublish`가 갖는다.
- 코드: `ContentPublishStatus`(DRAFT·PUBLISHED) · `ContentCategory`(ACADEMIC·EVENT·NEWS — 고정 enum) · `ContentSlug`(정규식·길이) · `ContentBody`(본문 상한 10만 자) · `ContentErrorCode`.
- 서비스: `ContentPageService` · `ContentPostService`(from-event 포함) · `ContentPostImageService`(갤러리) · `PublicContentService`(익명). «유일한 구현» 클래스: `ContentImageLocation`(오브젝트 키·공개 주소 조립).
- 컨트롤러: `ContentPageController`(`/v1/content/pages`) · `ContentPostController`(`/v1/content/posts`) — 둘 다 클래스 레벨 `@RequireAuthority(CONTENT_MANAGE)` · `PublicContentController`(`/public/v1/pages`·`/public/v1/posts`, permitAll).
- MCP: `global/mcp/tool/ContentTools` 여섯(`create_page`·`update_page`·`create_post`·`update_post`·`publish_content`·`request_content_image_upload`) + `ContentPagePatch`·`ContentPostPatch`.
- 접수 중인 폼 목록(`GET /public/v1/forms/open`)은 이 도메인이 아니라 **폼 도메인**(`PublicFormMetaController`·`PublicOpenFormResponse`)에 있다 — «접수 중»의 어휘(`FormReceiptPolicy`)가 거기 있어서다. 익명 콘텐츠 셋(ADR-0038)의 계약으로는 이쪽 테스트(`PublicContentControllerTest`·`PublicContentDtoContractTest`)가 함께 본다.

## 규칙

- `domain/content` — 공개 사이트(www)의 소개·연혁·운영진 같은 **페이지**와 활동 아카이브 **포스트**. 홍보국이 어드민(또는 MCP)에서 쓰고 게시하면 익명이 읽는다 (ssccops#381 · Epic ssccops#378 · [ADR-0038](https://github.com/SoongSilComputingClub/ssccops/blob/develop/docs/decisions/0038-anonymous-content-layer-pages-posts-open-forms.md) · V15·V16).
  - **페이지와 포스트는 다른 테이블이다.** 분류·활동일·요약·표지·갤러리는 포스트에만 있다 — 합치면 페이지 행의 절반이 NULL이고 목록 질의마다 «페이지가 아닌 것» 필터가 붙는다(폼 템플릿을 `form_tmpl`로 나눈 #142와 같은 판단). 슬러그 공간도 따로다(`uk_cntnt_page_slug`·`uk_cntnt_post_slug`) — 같은 slug가 양쪽에 있어도 충돌이 아니다(공개 주소가 `/pages/`·`/posts/`로 갈린다).
  - **이력 테이블은 변경 뒤 전체 스냅샷 한 행이다**(bfr_/aftr_ 쌍이 아니다). 생성·수정·게시·게시 취소 넷 다 한 행씩이며 «무엇을 했나» 구분 코드는 없다(직전 행과 비교하면 드러난다). 전 컬럼 `updatable = false`이고 지우는 경로가 없다 — 개인정보 처리방침 같은 페이지의 «그때 무엇이 게시돼 있었나»가 법적으로 필요한 값이라 한 행이 그 시점의 본문 전체를 든다. 스냅샷은 엔티티가 아니라 **서비스**가 찍는다(변경자 `@CurrentMember`·시각 `Clock`을 엔티티가 모른다). 이력의 마지막 행은 언제나 현재 모습과 같다.
  - **본문은 마크다운 원문 그대로다.** 서버는 길이(`ContentBody.MAX_LENGTH` = 10만 자 · 413 `CONTENT_TOO_LARGE` — 행사와 같은 값)만 보고 파싱·raw HTML 차단은 www 렌더러가 한다(ADR-0038 «본문 포맷 A»). 서버 sanitize를 두지 않은 것은 렌더러가 raw HTML을 끄면 작성자가 코드를 실행할 길이 애초에 없고, 서버가 마크다운을 파싱하면 www와 다른 파서 두 벌이 되기 때문이다.
  - **게시 상태는 둘뿐이다**(DRAFT·PUBLISHED). 행사의 ARCHIVED 같은 «내렸지만 보관»을 두지 않았다 — 내리는 것이 곧 초안으로 돌리는 것이고 삭제는 없다. 같은 상태로의 전이는 409(`CONTENT_ALREADY_PUBLISHED`·`CONTENT_NOT_PUBLISHED`) — 두 운영자가 같은 버튼을 동시에 누른 경우가 실제 경로라 두 번째 사람이 «이미 됐다»를 알아야 한다. 게시 취소는 `pub_dt`를 비운다(옛 시각은 이력 행이 든다).
  - **게시·게시 취소만 감사 로그에 남긴다**(`AuditAction.CONTENT_PAGE_PUBLISH/UNPUBLISH`·`CONTENT_POST_*`). 익명에게 열리고 닫히는 사건이 그것이고, 작성·수정은 이력 테이블이 본문째 든다. 감사에는 본문 값이 실리지 않는다(ADR-0024) — 대상 id와 결과 상태뿐이다.
  - **수정(PATCH)은 통째로 교체다**(운영 도메인 F2와 같은 계약). 부분 수정을 두지 않은 것은 nullable 필드(요약·행사·표지)를 «비운다»와 «안 바꾼다»로 가를 방법이 JSON에 없어서다. MCP `update_page`·`update_post`는 `ContentPagePatch`·`ContentPostPatch`로 읽고-합치기 한다(`McpPatchContractTest`가 필드 1:1을 본다) — 그래서 MCP로는 요약·행사·표지를 비울 수 없다(`WorkPatch`가 총평을 비우지 못하는 것과 같은 한계). slug도 바꿀 수 있다 — 게시 뒤 바꾸면 퍼진 링크가 깨지는데, 막는 것은 서버가 아니라 화면의 확인 문구다(오타 난 slug를 영영 못 고치는 것이 더 나쁘다).
  - **slug 규칙**(`ContentSlug`): 소문자·숫자·하이픈, 최대 80자. 숫자 id를 공개 주소에 쓰지 않는 것은 폼(ADR-0036)과 같은 이유(1부터 훑으면 초안 유무가 샌다)이고, 한글을 받지 않는 것은 퍼센트 인코딩된 주소가 메신저에서 읽히지 않아서다. 동시 생성은 UNIQUE 위반을 같은 409 `CONTENT_SLUG_DUPLICATED`로 옮긴다.
  - **from-event(`POST /v1/content/posts/from-event/{eventId}`)는 복사이지 연결이 아니다.** 행사의 제목·시작일(활동일)·장소·일시(본문 첫 줄 인용 블록)·본문을 복사한 DRAFT를 만들고 `event_id`를 채운다 — 그 뒤 행사가 바뀌어도 포스트는 따라가지 않는다. slug는 `event-{id}`, 겹치면 `-2`, `-3`…(같은 행사를 사진 편·후기 편으로 두 번 갈무리하는 것이 정상). 행사 도메인은 **한 방향으로만** 읽는다(`EventRepository.findByIdAndDeletedAtIsNull` — 지운 행사는 행사 도메인과 같은 404 `EVENT_NOT_FOUND`). `event_id`·`cover_file_id`를 엔티티 연관이 아니라 `Long`으로 둔 것은 조회마다 행사·파일을 끌어올 이유가 없고, 연관을 두면 지운 행사(`del_dt`) 규칙까지 이쪽이 알아야 해서다. `eventId`의 실재는 검증하지 않는다(FK는 실재만 본다) — 지운 행사를 가리켜도 www 링크가 404가 될 뿐이고 갈무리 글이 행사 삭제에 막혀서는 안 된다.
  - **갤러리는 새 테이블이 아니라 `file_rfrnc`(`FileTargetType.CONTENT_POST` · `trgt_id = post_id`)다** — #220이 그 테이블을 도메인 중립으로 연 이유가 이것이다. 행사 본문 이미지(#161)와 달리 **행을 남긴다**: 갤러리는 본문 링크가 아니라 «몇 장이 무엇인가»를 서버가 답하는 목록이고 표지(`cover_file_id`)가 그 행을 FK로 가리킨다. 키는 `content-posts/{postId}/{uuid}.{ext}`(`ContentImageLocation`), 발급·크기 상한·확장자·`contentType`을 서버가 정하는 계약은 행사와 같다(#210). **행은 발급 순간 생긴다** — PUT이 서버를 지나지 않아 실제로 올라왔는지는 모르고, 올리지 않은 장은 빈 칸으로 남아 운영진이 DELETE로 정리한다(«업로드 완료 확인» API는 기각 — R2 HEAD 왕복이 늘고 확인을 빠뜨린 스크립트의 사진이 영영 안 뜬다). 순서는 `file_rfrnc_id` 오름차순 = 발급 순서(정렬 컬럼 없음 — 손으로 바꾸는 요구가 생기면 그때 컬럼). 표지는 갤러리에 있는 파일이어야 한다(400 `COVER_NOT_IN_GALLERY` — 서비스가 본다, 엔티티는 파일 참조를 조회할 수 없다). 그 장을 지우면 표지를 먼저 비운다(FK). `FileReferenceService.findAllByTarget`·`add`·`deleteOneOfTarget`이 이 도메인을 위해 생겼다(단건 `findByTarget`을 갤러리에 쓰면 둘째 행부터 조용히 사라진다).
  - **익명 읽기 주소는 파일명이 아니라 `file_rfrnc` id로 연다**(`/public/v1/posts/{postId}/images/{fileId}` · 302 + 서명 GET 15분 · 리다이렉트 응답 캐시는 행사와 같다). 행사(파일명 열쇠)와 갈리는 지점 — 행이 있으므로 지운 장이 주소로 계속 열리는 상태를 만들지 않으려면 열쇠가 행이어야 하고, 그래서 `../`가 낄 파일명 검증 자체가 없다. 주소의 `postId`는 slug가 아니라 숫자 id다(slug는 바뀔 수 있고 본문에 굳은 주소가 깨져서는 안 된다). 게시본이 아니면 상세와 같은 404 `POST_NOT_FOUND`이고 그 다음이 `CONTENT_IMAGE_NOT_FOUND`다(순서가 바뀌면 초안의 파일 유무가 코드로 샌다).
  - **익명 응답 record는 어드민 record와 다르다**(`PublicContentPageResponse`·`PublicContentPostSummaryResponse`·`PublicContentPostDetailResponse`·폼의 `PublicOpenFormResponse`). 그 타입이 «실릴 수 있는 필드»의 상한이며 `PublicContentDtoContractTest`가 컴포넌트 이름을 금지 목록(수정자·변경자·이력·이름·이메일·연락처·학번·운영 타임스탬프)과 대조하고 ADR-0038의 표를 정확히 못 박는다. 필드를 늘리려면 ADR을 먼저 고친다. 게시 상태는 **질의 조건**이다(`findBySlugAndPublishStatus` — 조회 뒤 거르지 않는다, `PublicEventServiceImpl`과 같은 태도) — 초안과 없는 것이 같은 404다.
  - **익명 페이지 목록은 접두사가 필수다**(`GET /public/v1/pages?slugPrefix=` · `PublicContentPageSummaryResponse` — slug·제목·게시일, 본문 없음 · #513 · ssccops#425). www 역대 운영진이 «어떤 대수가 게시돼 있나»를 묻는 자리이며 그전에는 www가 손으로 적은 상수 `[44]`라 43대가 게시돼 있어도 갈 길이 없었다. 접두사 없이 «게시된 페이지 전부»는 열지 않는다 — 카탈로그는 www 코드가 알고, 이 경로는 «이 패턴의 게시본»만 답한다(ADR-0038 기준 안: 게시 상태인 것의 공개용 필드). 정렬은 slug 오름차순이고 대수 내림차순은 www가 숫자로 파싱해 한다.
  - **익명 응답에 `Cache-Control: public, s-maxage=300, stale-while-revalidate=600`**(`global/apipayload/PublicCacheControl` — 폼의 `forms/open`도 같은 값이라 global에 있다. content ↔ form 순환을 피하려는 배치). 브라우저 `max-age`는 두지 않는다(게시 취소가 브라우저 캐시에 남으면 «다시 열어 보라»고 말할 수 없다). 404에는 붙지 않는다(핸들러를 지나므로 시큐리티 기본 `no-store`만) — 게시 직후의 «아직 없음»이 CDN에 굳지 않게. 게시 취소가 최대 5분 늦는 것은 ADR-0038이 감수한 대가다.
  - **익명 목록 정렬은 활동일 역순, 같은 날은 id 역순**이고 커서는 그 두 값(`ContentPostCursor`)이다 — 활동일만 실으면 같은 날의 포스트가 경계에서 겹치거나 빠진다. 인덱스 `idx_cntnt_post_pub_list`가 이 질의 모양 그대로다. `totalCount`는 분류 필터 적용 건수, `overallCount`는 게시본 전체(www 탭의 «학술 12 · 전체 40»). 어드민 목록은 id 역순 커서(`ContentIdCursor`)다.
  - **권한은 `CONTENT_MANAGE` 하나다**(V16 · `SUPER` 직속 · 회장·부회장 명시 부여). 쪼갤 자식이 없는 것은 행사(`EVENT_MANAGE` · D8)와 같다. **시드에 홍보국장·홍보국원 역할이 없어** 그쪽 부여는 배포 뒤 역할별 권한 화면에서 한다(#65). `test` 프로필은 `data-locations`·`SeedScript.LOCATIONS`가 V16을 함께 가리킨다.

## 다른 도메인과 닿는 곳

- 행사: from-event가 `EventRepository`를 한 방향으로 읽는다. 행사는 콘텐츠를 모른다.
- 파일: 갤러리가 `FileReferenceService`(목록·추가·한 건 삭제)·`FilePresigner`·`ImageFileType`을 쓴다. «누가 볼 수 있는가»(게시본이면 익명)는 이 도메인이 답한다 — 파일 도메인은 묻지 않는다.
- 폼: `GET /public/v1/forms/open`은 폼 도메인에 있고 이 도메인은 폼을 부르지 않는다. 공유하는 것은 `PublicCacheControl`(global)뿐이다.
- 회원: 수정자·변경자 FK(`mdfcn_mbr_id`·`chg_mbr_id`). 응답에 이름(`mdfcnMbrNm`·`chgMbrNm`)을 싣는 것은 어드민·MCP 쪽뿐이다(ADR-0037).

## 테스트 함정

- 트랜잭션을 건 컨트롤러 테스트에서 **실패하는 요청은 마지막 하나**다(참여 트랜잭션의 rollback-only — event/AGENTS.md와 같다). 실패 뒤에 또 실패는 괜찮고 실패 뒤 성공이 `UnexpectedRollbackException`이다.
- `ContentPostImageControllerTest`는 `S3Presigner`를 `@MockitoBean`으로 갈아 키를 URL에 실어 돌려준다(행사 이미지 테스트와 같은 방식). 그래서 별도 컨텍스트다 — 갤러리가 필요 없는 테스트는 `ContentPostControllerTest`에 둔다.
- `PublicContentControllerTest`는 `Clock`을 고정한다(`forms/open`의 접수 중 판정이 주입된 Clock에서 온다).
- 감사 로그(`afterCommit`)는 `@Transactional` 테스트에서 돌지 않는다 — 게시 감사를 검증하려면 `AuditPointsTest` 방식(전용 H2 · 실제 커밋)이다.
- 자세한 판단은 `ContentPageServiceImpl`·`ContentPostServiceImpl`·`ContentPostImageServiceImpl`·`PublicContentServiceImpl`·`ContentImageLocation` 주석.
