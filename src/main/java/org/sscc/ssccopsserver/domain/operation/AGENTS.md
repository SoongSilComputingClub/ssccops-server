# domain/operation

이 도메인 규칙의 정본. 루트 AGENTS.md는 여기를 가리키기만 한다.

루트 AGENTS.md에는 이 도메인의 절이 없었다 — 규칙은 코드 주석에 있고, 이 문서는 어디를 읽을지만 가리킨다.

## 무엇이 있나

- 테이블 구조: `oper`(`OperationEntity` — 제목·기간·담당자·우선순위·`del_dt` 같은 공통 속성)를 업무 `WorkEntity`(`work`) · 하위 업무 `SubWorkEntity`(`sub_work`) · 회의 `MeetingEntity`(`mtg`)가 **확장**한다. PK=FK 상속이 아니라 자체 PK + `oper_id` FK이며 `@MapsId`를 쓰지 않는다(`OperationEntity` 주석).
- 하위 업무에 딸린 것: 유형 `SubWorkTypeEntity`(`sub_work_type`, 결재 권한 `autzr_authrt_cd`) · 상태 이력 `SubWorkStatusHistoryEntity` · 승인 `SubWorkApprovalEntity` · 투표 `SubWorkApprovalVoteEntity` · 반려 `SubWorkRejectionEntity` · 체크리스트 `SubWorkChecklistItemEntity`와 그 이력 `SubWorkChecklistHistoryEntity`(`V5`). 회의에 딸린 것: 안건 `MeetingAgendaEntity`(`mtg_dtl` — **안건은 언제나 운영 건을 가리킨다**: 제목이 그 `oper_ttl` 이고 안건이 따로 제목을 갖지 않는다. `V24` 가 `agnd_nm` 을 지우고 `oper_id` 에 NOT NULL 을 걸었다 · #593 · ADR-0055 — 그전에는 독립 안건이 설계에 있었는데 화면에 만들 길이 없었고 실제 데이터도 전부 연결형이었다).
- 업무 태그(#624 · ssccops#565 · V29): `WorkTagEntity`(`work_tag`) · `WorkTagRelationEntity`(`work_tag_rel`) · `WorkTagService` · `WorkTagController`. 주관 국도 태그다(별도 칸 없음).
- 서비스: `WorkService` · `SubWorkService`(전이·투표·체크리스트) · `SubWorkTypeService` · `MeetingService` · `ApprovalService`(승인함) · `DashboardService` · `OperationService`(운영 통합 조회). 승인함·대시보드·통합 조회는 **다른 서비스의 조회를 그대로 불러 조립만 한다** — 필터·정렬·집계 규칙을 새로 만들지 않는다(각 Impl 주석).
- «유일한 구현» 클래스: `ApprovalAuthorityPolicy`(누가 승인·반려·투표할 수 있는가 — 판정 재료는 권한이다, #123) · `SubWorkOwnershipPolicy`(이 하위 업무를 다룰 수 있는 사람인가 — `WORK_MANAGE` 또는 담당자 본인, #101) · `DeadlinePolicy`(마감 경계는 **일자**, #121).
- 포트 구현: `SubWorkOwnerLoadProvider`(회원의 `MemberSubWorkLoadProvider`) · `SubWorkSharePreviewProvider`·`WorkSharePreviewProvider`·`MeetingSharePreviewProvider`(공유의 `SharePreviewProvider`). 포트 구현을 서비스 구현체에 얹지 않는 이유는 `SubWorkOwnerLoadProvider` 주석.

## 규칙

- 전이 판단은 엔티티가 한다 — `SubWorkEntity.applyTransition`·`requireChecklistEditable`·`requireVotable` · `MeetingEntity.applyTransition`·`requireAgendaEditable`. 서비스(`SubWorkServiceImpl.transitionSubWork`)는 조회·기록·집계만 맡는다.
- 승인·완료·반려는 유형이 지정한 결재 권한 보유자만, 찬반 투표는 `APPROVAL_VOTE` 보유자만 — 자격의 시드와 표시명 규칙은 회원 도메인 문서의 «승인·투표 자격도 권한이 준다» 항목(`../member/AGENTS.md`). 자가 승인 판정은 담당자가 아니라 **등록자** 기준이다(`SubWorkServiceImpl` 주석).
- 삭제는 자기 `oper`만 소프트 삭제(`del_dt`, #125)하고 이미 지운 건은 409 `ALREADY_DELETED`다 — 폼·행사의 소프트 삭제가 이 코드 문자열을 따라갔다. 삭제 권한은 `WORK_DELETE`·`MEETING_DELETE`로 따로 있다.
- 지연·마감임박 판정은 응답값(`SubWorkEntity.isDelayedBefore`)과 목록 필터(`SubWorkRepositoryImpl`)가 `DeadlinePolicy` 한 곳의 경계를 함께 쓴다 — 각자 오늘 0시를 계산하지 않는다.
- 쿼리 수는 테스트가 못 박는다(승인함 12회 · 상세·목록도 각각) — 세지 않은 쿼리는 늘어도 아무도 모른다(#62). 페이지 단위 집계로 N+1을 막는다.
- 회의 전이(개회·회의록작성·종료)는 회의 책임자 본인만, 책임자는 언제나 `oper.pic_id`와 같다(`MeetingEntity` 주석).
- **첨부는 oper에 붙는다** (#493 · ssccops#410 · ADR-0042 «추가»라 이력 대상 아님). `/v1/operations/{operationId}/attachments` 하나로 업무·하위 업무·회의를 다 받는다 — 셋이 전부 `oper`의 확장이라서. `FileTargetType.OPERATION` + `file_rfrnc`의 메타 열(V18 `orgnl_file_nm`·`file_size`·`rgtr_mbr_id`·`crt_dt`). 권한은 `@RequireAuthority`가 아니라 `OperationAttachmentAccessPolicy` — 종류마다 그 건을 보는/고치는 권한 그대로(업무 `WORK_READ`/`WORK_MANAGE` · 하위 업무 읽기 `WORK_READ`, 쓰기 담당자 또는 `WORK_MANAGE` · 회의 `MEETING_READ`/`MEETING_MANAGE`). 형식은 `AttachmentFileType`(문서·표·발표·압축·이미지 · 이름의 확장자로), 상한 25MB(이미지 10MB와 별개), 발급이 곧 참조 행(PUT 실패는 웹이 DELETE), 내려받기는 원본 이름을 `response-content-disposition`에 실은 서명 URL을 **JSON**으로(`…/download-url` — 인증 경로라 브라우저 이동으로는 헤더를 못 붙여 302가 안 된다), 삭제만 감사 로그(`operation.attachment.delete`).

- **업무 태그는 폼 라벨의 모양이되 표는 따로다** (#624 · ssccops#565). 목록은 따로 관리하고(`/v1/work-tags` · 읽기 `WORK_READ`, 생성·이름 변경·삭제 `WORK_MANAGE` · 이름 중복 409 `WORK_TAG_NAME_DUPLICATED`) 업무에는 고르기만 하며, 지정은 **전체 교체**(`PUT /v1/works/{workId}/tags` · `WORK_MANAGE`)다 — 유지되는 지정은 다시 만들지 않아 지정 시각이 남는다(`FormLabelServiceImpl.replaceFormLabels`와 같은 비교식). 폼 라벨과 갈리는 자리 셋: **`use_yn`이 없고 지우면 지정도 함께 지운다**(업무는 그대로 · DB도 `ON DELETE CASCADE`) · **이름을 바꿀 수 있다**(국 이름이 바뀌면 달린 업무가 따라간다) · **관리와 지정이 같은 권한이다**(업무 수정이 이미 `WORK_MANAGE`라 가를 사람이 없다). 기각: 폼 라벨 표 공유(권한·목록이 다르다) · `work`에 `DEPT` 역할 FK(시드에 DEPT 역할 0건 · 권한 체계와 엮인다) · 입력하면 태그 생성(다시 난잡해진다) · 태그 하나씩 추가·삭제 API(폼 라벨과 모양이 갈리면 MCP·화면이 두 벌).
- **목록·상세의 태그 칩은 `WorkRepository.findTagRelationsByWorkIds` 한 번이다** — 상세 쿼리 3 → 4 · 목록 5 → 6(`WorkServiceImplTest`·`WorkServiceImplSearchTest`가 못 박는다). `WorkTagRelationRepository`가 아니라 `WorkRepository`에 둔 것은 `WorkServiceImpl` 생성자를 넓히지 않기 위해서다 — 테스트 일곱 곳이 `new WorkServiceImpl(...)`을 직접 부른다. 목록 필터 `tagId`는 join이 아니라 `exists`다(`WorkRepositoryImpl`) — 페이징 + fetch join 쿼리에 관계를 join하면 태그 수만큼 행이 불어 커서·건수가 어긋난다. 없는 태그 id는 400이 아니라 빈 결과다. 태그 `usageCount`는 **지운 업무를 세지 않는다**(폼 라벨은 센다).

## 다른 도메인과 닿는 곳

- 알림: `SubWorkServiceImpl.transitionSubWork`가 끝에서 `SubWorkTransitionedEvent(subWorkId, action, performerId)`(`operation/event/`)를 `ApplicationEventPublisher`로 발행한다 (ssccops#446 · ADR-0045). **이 도메인은 누가 듣는지 모른다** — 알림 도메인이 AFTER_COMMIT + `@Async`로 듣는다. 포트 인터페이스가 아니라 이벤트인 이유는 그 record 주석에 있다(알림은 전이를 막으면 안 된다 · 롤백된 전이의 알림은 없어야 한다). 테스트가 `new SubWorkServiceImpl(...)`을 직접 부르면 마지막 인자가 `ApplicationEventPublisher`다(`event -> {}`).

- 회원: `SubWorkOwnerLoadProvider`가 담당 건수를 답한다. 인가 판정은 회원 도메인 `AuthorityPolicy`를 부르고 여기서 펼침을 다시 적지 않는다(BR-M28).
- 공유: 하위 업무·업무·회의의 공유 링크 발급·폐기 엔드포인트는 이 도메인 컨트롤러에 있고(`WORK_READ`), 미리보기 내용은 `*SharePreviewProvider`가 만든다.
- MCP: 1차 도구 9종(`global/mcp/tool/OperationTools`)이 이 도메인의 REST를 자기 호출한다 — 루트 AGENTS.md «MCP» 절.

## 테스트 함정

- 승인함·목록 테스트는 쿼리 횟수를 숫자로 못 박는다 — 조회를 하나 더하면 그 숫자를 함께 고친다(주석의 셈도).
- 트랜잭션을 건 컨트롤러 테스트에서 **실패하는 요청은 마지막 하나**다 — 근거는 `../member/AGENTS.md`의 `RoleManageSelfLockGuard` 항목(참여 트랜잭션의 rollback-only 표시).
- `MemberChangeControllerTest`가 `MemberSubWorkLoadProvider`를 `@MockitoBean`으로 바꾼다 — 그래서 포트 구현이 `SubWorkServiceImpl`에 얹혀 있으면 안 된다(`SubWorkOwnerLoadProvider` 주석).
- 자세한 판단은 `SubWorkServiceImpl`·`MeetingServiceImpl`·`ApprovalServiceImpl`·`DashboardServiceImpl` 주석.
