# domain/file

이 도메인 규칙의 정본. 루트 AGENTS.md는 여기를 가리키기만 한다.

## 무엇이 있나

- `FileReferenceEntity`(`file_rfrnc`) · `FileReferenceService` · `FilePresigner` · `FileEraser` · `FileCopier` · 코드 `ImageFileType`·`FileTargetType`. 컨트롤러가 없다 — 이 도메인은 다른 도메인의 서비스가 부르는 쪽이다.
- 하지 않는 것: 접근 제어 · 읽기 주소 조립 · 대상당 건수 제한(아래 «올라오지 않는 것» 항목).

## 규칙

- `domain/file` — **파일이 버킷의 어디에 있는가**를 아는 유일한 도메인 (#220). 여기 있는 것은 다섯뿐이다: `file_rfrnc` 행(`entity/FileReferenceEntity` · `service/FileReferenceService`), 서명(`service/FilePresigner`), 업로드 허용 형식(`code/ImageFileType`), 삭제(`service/FileEraser` · #234), 복사(`service/FileCopier` · ssccops#198).
  - **`FileEraser`는 커밋 뒤에 지우고 `FileCopier`는 트랜잭션 안에서 복사한다.** 방향이 반대로 보이지만 같은 규칙이다 — **잘못된 데이터보다 고아가 낫다.** 삭제는 되돌릴 수 없어 롤백된 변경 뒤에 "DB는 옛 상태인데 파일만 없는" 조합을 남기면 안 되고, 복사는 실패했을 때 그대로 롤백돼야 "본문이 없는 오브젝트를 가리키는 사본"이 커밋되지 않는다(성공한 뒤 DB가 롤백되면 남는 것은 아무도 참조하지 않는 오브젝트, 즉 비용뿐이다). 복사는 **서버 측 `CopyObject`**이며 바이트가 서버를 거치지 않는다 — 내려받아 다시 올리면 멀티파트를 두지 않은 이유(#107)가 복제 경로에서 되살아난다. **원본이 없는 것은 실패가 아니다**(`false` 반환): 서버는 PUT을 관측하지 않아 본문의 참조가 실물을 가리킨다는 보장이 애초에 없고, 원본에서 이미 깨진 이미지가 행사 복제를 통째로 막으면 운영자는 그것을 본문에서 찾아 지우기 전까지 아무것도 못 한다.
  - **`file_rfrnc`는 학술 전용이 아니다.** #137이 회차 출석 인증사진용으로 만들었고 `sesn_id` NOT NULL + UNIQUE가 도메인을 가르고 있었는데, 파일이 붙는 자리가 늘 때마다 테이블을 새로 만들지 않으려고 소유자를 **`trgt_se_cd`(대상_구분_코드) + `trgt_id`(대상_ID)** 두 값으로 열었다. 전제는 #200이 이미 깔아 두었다 — 저장 값이 조립된 URL이 아니라 **오브젝트 키**라 도메인 중립이다. 대상을 늘리는 일은 `FileTargetType`에 한 줄과 표준코드 시트에 한 줄이며, 지금 코드값은 `SESSION` 하나다.
  - **FK를 걸지 않는다.** 대상 테이블이 여럿이라 걸 수 없고, 배타적 FK(`sesn_id`·`event_id`·… + CHECK)로 가면 대상이 하나 늘 때마다 스키마와 데이터사전이 바뀐다(`ddl-auto: update`가 nullable 전환을 반영하지 않아 그때마다 dev·prod 수동 ALTER가 붙는다). 대가는 고아 행이고 **정리는 각 도메인의 책임**이다 — 애초에 이 행은 실물을 가리킨다는 보장이 없다(서버가 PUT을 관측하지 않는다).
  - **올라오지 않는 것이 이 도메인의 요점이다.** ① **접근 제어** — "누가 볼 수 있는가"는 도메인마다 다르다(학술 인증사진은 팀원·리더·`ACADEMIC_PROGRAM_MANAGE` · 행사 이미지는 게시된 행사면 익명). `FileReferenceService`에 대상별 분기표를 만들면 그것이 곧 인가 규칙 두 번째 벌이 되고, 두 번째 벌은 화면과 갈린 채로 자란다. ② **읽기 주소 조립** — 학술은 서명 URL을 응답에 직접 싣고 행사는 우리 도메인의 영구 리다이렉트 주소를 본문에 굳힌다. ③ **대상당 몇 건인가** — 회차당 1장은 학술의 규칙이다.
  - **`FilePresigner`가 서명을 만드는 유일한 자리**이고 TTL(업로드 10분 · 읽기 15분)도 여기 있다. 그전에는 학술·행사가 각자 `S3Presigner`와 버킷 이름을 주입받아 네 곳에서 서명했고, 그 값이 갈리면 실패가 서버 로그가 아니라 브라우저에서만 보인다. **누구에게 내주는지는 묻지 않는다** — 판정은 부르는 쪽이 이미 끝냈어야 하고, 순서가 뒤집히면(서명해 두고 응답에서 거른다) 한 줄만 어긋나도 새어 나간다. 그래서 학술은 `SessionFileReferenceViewer`가 자격을 본 뒤 이 클래스를 부르는 모양을 유지한다.
  - **대상당 1건 제약은 부분 유니크 인덱스 + 애플리케이션 판정 두 겹이다.** `uk_file_rfrnc_sesn ON file_rfrnc (trgt_id) WHERE trgt_se_cd = 'SESSION'`은 PostgreSQL 전용이라 **H2에는 없고**(#143의 초안 1건 제약과 같은 자리), 그래서 `SessionFileReferenceServiceImpl.upsert`의 **`sessionRepository.lockById` → 조회** 순서가 유일한 방어선인 환경이 있다. 잠그는 대상이 `file_rfrnc`가 아니라 `sesn`인 것은 참조 행이 아직 없을 수 있어서이며(잠글 행이 없으면 아무것도 막지 못한다), 그래서 이 잠금은 파일 도메인이 대신해 줄 수 없다. `@Table`에 UNIQUE를 두지 않는 것은 그것이 대상 전체에 걸려 다중 첨부가 정상인 대상이 생기는 순간 테이블을 다시 갈라야 하기 때문이다.
  - **행사 본문 이미지(#161·#208)는 이 테이블을 쓰지 않는다.** 그쪽은 의도적으로 DB에 아무것도 남기지 않으며 본문 마크다운의 링크가 곧 참조다 — 행을 남기면 서버가 관측할 수 없는 사건(PUT 성공 · 본문에서 삭제)에 대한 행이 쌓이기만 하고 아무도 지우지 않는다. 두 도메인이 공유하는 것은 `FilePresigner`와 `ImageFileType`뿐이다.
  - **`dev`·`prod`는 `ddl-auto: update`라 이 변경을 반영하지 않는다.** 배포와 **동시에** 실행할 것(중간 상태에서는 앱이 NOT NULL 컬럼에 값을 못 넣는다): `ALTER TABLE file_rfrnc ADD COLUMN trgt_se_cd VARCHAR(20);` · `ALTER TABLE file_rfrnc ADD COLUMN trgt_id BIGINT;` · `UPDATE file_rfrnc SET trgt_se_cd = 'SESSION', trgt_id = sesn_id;` · 두 컬럼 `SET NOT NULL` · `ALTER TABLE file_rfrnc DROP CONSTRAINT uk_file_rfrnc_sesn;` · `ALTER TABLE file_rfrnc DROP COLUMN sesn_id;` · `CREATE UNIQUE INDEX uk_file_rfrnc_sesn ON file_rfrnc (trgt_id) WHERE trgt_se_cd = 'SESSION';`. **행이 없으면 `DROP TABLE file_rfrnc;` 후 재생성이 더 깨끗하다**(#178의 학술 테이블 정리와 같은 판단 — 다만 배포보다 먼저 지우면 옛 이름을 쓰는 앱이 다시 만든다).

## 다른 도메인과 닿는 곳

- 학술: 회차 출석 인증사진(`FileTargetType.SESSION`) — 자격 판정은 `SessionFileReferenceViewer`가 끝낸 뒤 `FilePresigner`를 부른다. 대상당 1건 잠금은 학술이 `sesn` 행에 건다.
- 행사: 본문 이미지는 `FilePresigner`·`ImageFileType`만 쓰고 `file_rfrnc`를 쓰지 않는다. 복제는 `FileCopier`, 삭제는 `FileEraser`.
- `S3Client`·`S3Presigner` 빈은 `global/config/R2Config`(루트 AGENTS.md).

## 테스트 함정

- `FileEraser`는 커밋 **뒤**에 지운다 — `FileEraserTest`·`FileReferenceUpsertEraseTest`가 그 시점을 본다. `@Transactional` 테스트에는 커밋이 없어 `afterCommit`이 돌지 않는다.
- 부분 유니크 인덱스 `uk_file_rfrnc_sesn`은 H2에 없다(위 항목).
- 자세한 판단은 `FileEraser`·`FileCopier`·`FilePresigner` 주석.
