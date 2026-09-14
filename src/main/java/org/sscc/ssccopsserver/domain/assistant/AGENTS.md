# domain/assistant

이 도메인 규칙의 정본. 루트 AGENTS.md는 여기를 가리키기만 한다.

**아직 스키마와 배선뿐이다** (#396 · Epic ssccops#321). 업로드(#399) · 파서(#397·#398) ·
색인 워커(#400) · 목록·적용 전환(#401) · 질의(#403)는 아직 없다 — 그 이슈들이 여기 규칙 위에
얹힌다. 결정은
[ADR-0028](https://github.com/SoongSilComputingClub/ssccops/blob/develop/docs/decisions/0028-rag-assistant-on-existing-stack.md)(스택)과
[ADR-0029](https://github.com/SoongSilComputingClub/ssccops/blob/develop/docs/decisions/0029-rag-corpus-owned-by-screen.md)(코퍼스)에 있다.

## 무엇이 있나

- 엔티티: `RagDocumentEntity`(`rag_doc`) — 문서 판본. 코드 enum 셋(`RagDocumentType` ·
  `RagIndexStatus` · `RagApplyStatus`)과 오류 코드 `AssistantErrorCode`.
- 서비스: 포트 `RagChunkStore`와 그 구현 `PgVectorRagChunkStore` · 기능 플래그 `AssistantFeature`.
- 빈 배선은 `global/config/AssistantConfig`(`ChatClient` · `RagChunkStore`)다.
- **컨트롤러가 없다.** 지금 이 도메인에는 열린 경로가 하나도 없다.
- 벡터 청크는 `vector_store`에 들어가며 **그 테이블에는 엔티티가 없다** — 스키마도 이름도
  Spring AI가 정하고 우리는 `V10`으로 옮겨 적기만 한다.

## 규칙

- **행이 만들어지는 경로는 업로드 API 하나다**(ADR-0029 · #399). 레포 커밋도 Gradle 태스크도
  적재 경로가 아니다. 옛 안(회칙을 md로 커밋하고 태스크로 적재)을 버린 것은 ① **규정 개정이
  총회에서 나오는데** 커밋 코퍼스면 고칠 수 있는 사람이 «배포할 수 있는 사람»뿐이고,
  ② **참조해야 할 문서의 절반이 우리가 쓴 것이 아니기**(학칙 발췌·세칙·지침이 PDF·DOCX로 온다)
  때문이다. 그 태스크가 개발자 노트북에서 운영 DB에 붙어 도는 경로라 «누가 언제 무엇을
  바꿨는가»가 남지 않는다는 것도 함께 걸렸다 — 그 «누가»가 `rag_doc.rgtr_mbr_id`다.
- **상태가 두 축이다 — 한 컬럼에 겹치지 않는다.** 색인 진행(`indx_stts_cd`)과 적용
  여부(`aplcn_stts_cd`)를 합치면 «색인은 끝났지만 아직 시행 전인 개정안»을 표현할 수 없는데,
  **첫 업로드 대상이 바로 그것이다**(회칙 개정안의 부칙이 발효일을 «개강총회 의결일»로 비워
  두고 있다). 두 축이라서 «색인 중인 문서를 질의가 보는가»에도 답할 수 있다 — 검색 조건이
  `INDEXED AND EFFECTIVE` 둘이며 **조회한 뒤 거르지 않고 검색 필터에 넣는다.**
  - 전이표는 두 enum이 갖고 **거절은 엔티티(`RagDocumentEntity`)가 던진다.** 서비스에 옮겨
    적으면 색인·전환 경로가 늘 때마다 규칙이 복제된다(`FormEntity.changeStatus`와 같은 자리).
  - **`INDEXED`가 아니면 `EFFECTIVE`로 올릴 수 없다**(409 `RAG_DOCUMENT_NOT_INDEXED`). 통과시키면
    «시행 중인데 검색되지 않는 문서»가 되어 도우미가 근거 없이 침묵한다 — 화면에는 반영됐다고
    뜨는데 답변만 달라지지 않는, 아무도 원인을 찾지 못하는 종류의 고장이다.
  - **`SUPERSEDED`는 종착점이다.** 옛 판본을 되살리는 길을 열면 «지금 유효한 규정»에 답이 둘이
    된다. 되돌리려면 그 파일을 새 판본으로 다시 올린다(원본이 R2에 있다).
  - **색인 상태는 사람이 정하는 값이 아니다** — 워커가 적는다. 그 컬럼만 고치는 API를 열지 않으며
    화면의 «재색인»은 상태 지정이 아니라 `PENDING`으로 다시 줄을 세우는 조작이다
    (`requeueIndexing`). 기동 복구(`INDEXING → PENDING`, 기획안 §12.4)도 **같은 메서드**다 —
    결과가 같은데 나누면 복구 경로만 흔적을 남기지 않는 규칙이 생긴다.
  - **자동 재시도가 없다.** 실패의 대부분이 쿼터·문서 자체이고 자동 재시도는 쿼터 소진을
    가속한다. 사유는 `fail_rsn_cn`에 남으며, **워커는 요청 밖에서 돌아 돌려줄 응답이 없으므로 그
    컬럼이 오류 코드의 자리를 대신한다.**
- **시행 중인 판본은 문서당 하나 — 두 겹이다.** PostgreSQL은 부분 유니크 인덱스
  (`uk_rag_doc_effective ON rag_doc (doc_cd) WHERE aplcn_stts_cd = 'EFFECTIVE'`), **H2에는 부분
  인덱스가 없어** 애플리케이션 판정(`findByDocumentCodeAndApplyStatusForUpdate`로 잠그고 같은
  트랜잭션에서 내린다)이 **유일한 방어선인 환경이 있다**(초안 1건 규칙 #143 · 폼 전속 #347과
  같은 모양). `@Table`에 조건 없는 UNIQUE를 달 수 없는 것은 그것이 같은 문서의 판본을 둘째부터
  막기 때문이다. 인덱스 모양은 `FlywayMigrationValidateTest`가 본다 — H2에 없는 제약이라
  거기서만 검증된다. **잠금·전환 자체는 #401의 몫이다.**
- **판본 번호는 세지 않고 최대값 + 1이다**(`findMaxVersion`). **삭제가 하드라** 행 수와 번호가
  갈리고, 세면 이미 쓴 번호를 다시 배정해 `uk_rag_doc_doc_cd_ver`에 걸린다.
- **`doc_cd`가 판본을 가로지르는 열쇠다 — 제목·파일명으로 대신하지 않는다**(`sys_form_cd`가
  폼에서 한 일과 같은 자리 · #140). 제목으로 묶으면 운영진이 제목을 다듬는 순간 «같은 문서»가
  둘로 갈린다. 표준코드 그룹이 아니라 문자열인 것은 사전에 코드그룹으로 등재하지 않았기
  때문이며(ssccops#325), 어휘(`REGULATION`·`SCHOOL_RULE`·…)는 운영 규칙이고 코드가 강제하지 않는다.
- **삭제는 하드다 — `del_dt`가 없다**(ADR-0029). 폼(#329)·행사(#347)의 소프트 삭제와 갈리는 것은
  그쪽이 «치우기»이고 이쪽은 «잘못 올린 파일을 없었던 것으로 만들기»이기 때문이다. 남길 값이 있는
  옛 판본은 `SUPERSEDED`가 이미 맡고, 질의 로그를 두지 않으므로 «과거 답변의 인용이 그것을
  가리킨다»는 근거도 성립하지 않는다. **그래서 되살리기가 없다.**
- **유형은 확장자가 정하고, 그것이 인용의 모양을 정한다**(`.md` → `STRUCTURED`, 조항 인용 ·
  `.pdf`·`.docx` → `GENERIC`, 페이지 인용). **요청이 유형을 신고하지 않는다**(#210과 같은 판단 —
  판정에 쓰는 값이 하나뿐이면 어긋날 수 없다). 평문에서 «제○조»를 정규식으로 긁어 조항 인용을
  흉내 내지 말 것 — **맞을 때도 틀릴 때도 있는 인용은 없는 인용보다 나쁘다.**
- **검색·적재는 포트(`RagChunkStore`) 뒤에 있다.** `test` 프로필은 H2라 pgvector 자동 구성이
  제외돼 있고 임베딩 모델도 없어 `VectorStore` 빈 자체가 서지 않는다. `@MockitoBean`으로 꽂으면
  **그 테스트마다 컨텍스트가 하나씩 갈리므로**(#103이 58→25로 줄인 이득) `@TestConfiguration`
  한 벌(`support/AssistantStubConfig`)을 여러 테스트가 함께 import 한다 — ADR-0009의
  `TestJwtDecoderConfig`와 같은 모양이다.
  - 포트가 Spring AI 타입(`Document`·`SearchRequest`)을 그대로 쓰는 것은 **격리하려는 것이
    «프레임워크»가 아니라 «실제 PostgreSQL 연결»**이기 때문이다. 청크를 만드는 쪽도 검색하는 쪽도
    이미 `Document`를 다루므로, 경계에서 변환하면 같은 값에 두 모양이 생긴다.
  - **`metadata.ragDocId`가 소유 관계의 전부다**(`RagChunkStore.RAG_DOCUMENT_ID_KEY`).
    `vector_store`는 프레임워크의 테이블이라 컬럼을 더할 수 없어 FK가 없고, 재색인·삭제는 이
    key로 지운다 — **빠뜨린 청크는 아무도 지울 수 없는 고아가 된다.**
  - **재색인은 새 청크를 넣기 직전에 옛 청크를 지운다**(#400). 순서를 뒤집으면 중간에 실패했을 때
    같은 조가 두 번 검색된다.
- **기능 플래그 `ssccops.assistant.enabled`(기본 `false`)** — `AssistantFeature`. 원래 #404의
  항목인데 **머지가 곧 dev 배포라**(#202) 플래그가 거기 있으면 색인 워커가 스위치 없이 먼저 뜨고,
  그 시점에 `PENDING` 행 하나로 무료 임베딩 쿼터를 태우기 시작하는데 끄는 방법이 revert뿐이다.
  **질의·적재·워커를 함께 닫는다** — 질의만 닫으면 워커가 계속 임베딩을 부르는데 끄는 이유가
  대개 쿼터다. 설정 파일에 키를 두지 않고 배포 환경변수(`SSCCOPS_ASSISTANT_ENABLED=true`)로만
  켠다(회원 하드 삭제 #361과 같은 판단 · 환경변수의 정본은 Coolify다).
- **오류 코드의 계단**: 플래그 off → 404 `ASSISTANT_DISABLED`(없는 자원의 `NOT_FOUND`와 코드를
  나눈다 — 웹의 플래그와 서버의 플래그가 갈렸을 때 «문서가 사라졌다»로 읽히면 안 된다) ·
  키가 없어 배선이 서지 않음 → 503 `ASSISTANT_UNAVAILABLE`(켜 두고 설정이 덜 된 상태라 요청의
  잘못이 아니다) · 전이표 위반 → 400 · 색인 전 시행 → 409.

## 스키마 — `V10__create_assistant_tables.sql`

- **스타터가 만들게 두지 않는다** — `spring.ai.vectorstore.pgvector.initialize-schema: false`.
  dev·prod가 `ddl-auto: validate`라 마이그레이션이 모르는 테이블이 생기면 «baseline +
  마이그레이션이 곧 DB»라는 전제가 깨진다(ssccops#213).
- **`vector_store`의 이름과 컬럼은 Spring AI가 정한다.** 우리 표준단어로 개명하면 프레임워크가
  찾지 못하므로 데이터사전에도 그 이름 그대로 등재했다(ssccops#325). 스타터의 DDL에서 셋을
  바꿨고 이유는 파일 상단에 있다 — `gen_random_uuid()`(확장 하나를 덜 깐다) · `metadata jsonb`
  (필터가 `::jsonb`로 훑는다) · `content`·`embedding`에 NOT NULL(비면 검색에 걸리지 않는 죽은 청크다).
- **`embedding vector(768)`이고 `spring.ai.vectorstore.pgvector.dimensions`도 768이다.**
  두 값이 같아야 하며, **후자를 비워 두면 저장소가 부팅 중에 모델에 차원을 묻는데 그 값은 모델
  이름 상수표를 먼저 보고 3072을 답할 수 있다**(#395 실측). 3072이면 pgvector 인덱스 상한
  2,000을 넘어 **재적재 없이는 인덱스를 못 건다.**
- **HNSW·IVFFlat 인덱스를 만들지 않는다**(ADR-0028) — 청크 수천 개면 순차 스캔이 빠르고 Free
  티어의 진짜 제약은 디스크가 아니라 RAM이다. **청크 3,000에 닿으면 다시 본다.**
- **`distance-type`을 기본값(`cosine-distance`)에서 움직이지 말 것.** 768로 자른 임베딩은 모델에
  따라 정규화가 안 돼 있는데 Spring AI는 정규화하지 않는다 — 지금 무해한 것은 코사인이 스케일
  불변이기 때문이고, `euclidean`·`inner-product`로 바꾸면 **검색이 조용히 망가진다.**
- ⚠️ **`vector` 확장은 Supabase에서 `extensions` 스키마에 깔린다**(dev·prod 둘 다 v0.8.2 ·
  ssccops#324). 타입 이름이 해석되려면 `search_path`에 그 스키마가 있어야 하는데 `postgres`
  역할에는 이미 걸려 있다 — **전용 DB 역할을 만들게 되면 그 역할에도 같은 설정이 필요하다.**
  `FlywayMigrationValidateTest`는 빈 DB라 `public`에 설치되므로 **이 조건을 검증하지 못한다.**
- **시드가 없다.** 코퍼스가 업로드로 들어오므로 **새 환경의 정상 상태는 «문서 0건»**이고, 그
  상태에서 질의는 «찾지 못했습니다»로 답한다. 기동 시 자동 적재를 붙이지 않는 것은 그것이 곧 두
  번째 적재 경로이고 무엇보다 **기동마다 임베딩 API를 부르는 코드**가 되기 때문이다.

## 다른 도메인과 닿는 곳

- **나가는 방향만 있다** — `assistant → member`(등록자) · `assistant → file`(원본 파일, #399부터).
  반대로 다른 도메인이 이 도메인을 부를 일은 없어야 한다(`DomainCycleTest`가 본다).
- `rag_doc.rgtr_mbr_id`는 **행위자 참조**라 `ON DELETE CASCADE`가 아니다(#361 · V9). 회원 하드
  삭제는 이 행이 있으면 409로 막히며, 그 문구와 미리보기의 `blockedBy`는
  `MemberReferenceConstraints`가 준다 — **거기 한 줄이 빠지면 미리보기가 «막는 것 없음»이라고
  답한 뒤 삭제가 번역되지 않은 409로 실패한다.**
- 권한 `RAG_DOCUMENT_MANAGE`와 `FileTargetType.RAG_DOCUMENT`는 **#402가 세운다.** 코퍼스를
  바꾸는 조작에만 붙고 질의는 인증만 요구한다 — 코퍼스 변경은 모든 답변의 근거를 갈아치우는
  조작이고, 프롬프트 인젝션 완화의 첫째 층이다(ADR-0029).

## 테스트 함정

- **`FlywayMigrationValidateTest`의 Testcontainers 이미지가 `pgvector/pgvector:pg17`이다.**
  기본 `postgres` 이미지에는 확장이 없어 V10의 `CREATE EXTENSION`에서 멈춘다. 그래서
  `./gradlew test`에 **Docker가 필요하다**(이 한 클래스 때문이라는 사실은 그대로다).
- **H2에 없어서 그 테스트에서만 확인되는 것 둘** — 부분 유니크 인덱스와 `vector` 타입. 일반
  테스트는 `vector_store`를 아예 만들지 못한다.
- **키가 없는 컨텍스트가 뜨는지는 `AssistantWiringTest`가 본다.** pgvector 자동 구성이 제외되지
  않으면 «규정 도우미와 아무 상관 없는 모든 테스트»가 함께 죽는다 — 그 제외는
  `GeminiWiringEnvironmentPostProcessor`가 임베딩 키 하나에서 파생해 건다.
- 스텁(`InMemoryRagChunkStore`)은 **유사도를 흉내 내지 않는다.** 넣은 순서대로 `topK`개를
  돌려줄 뿐이며, 순위를 지어내면 «검색이 무엇을 골랐나»를 확인하는 테스트가 스텁의 규칙을
  검증하게 된다. 검색 품질은 골든셋(#405)이 실제 스택에서 본다.
