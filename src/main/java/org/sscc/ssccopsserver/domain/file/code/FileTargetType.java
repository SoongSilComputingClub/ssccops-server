package org.sscc.ssccopsserver.domain.file.code;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/*
 * 파일 참조가 붙는 대상의 종류 (file_rfrnc.trgt_se_cd · #220).
 *
 * **이 값과 대상_ID 둘이 소유자를 가리키며 FK는 없다.** 대상 테이블이 여럿이라 걸 수 없고,
 * 그래서 부모가 지워져도 참조 행은 남는다 — 정리는 각 도메인의 책임이다. 원래부터 이 행은
 * 실물을 가리킨다는 보장이 없으므로(서버가 PUT을 관측하지 않는다, FileReferenceEntity 주석)
 * 새로운 종류의 문제가 아니라 이미 감수하던 성질이 하나 느는 것이다.
 *
 * **접두사를 대상이 갖는 것은 그 값이 대상마다 다르기 때문이다.** 키를 만드는 쪽과 옛 URL에서
 * 키를 되돌리는 쪽(FileReferenceEntity.objectKey)이 같은 값을 봐야 하는데, 공통 엔티티에
 * 상수 하나로 두면 대상이 늘어나는 순간 그 상수가 학술 전용이라는 사실이 드러난다.
 *
 * **값 목록과 `V13__widen_file_target_check.sql`이 정본 한 쌍이다**(#443). `file_rfrnc.trgt_se_cd`의
 * CHECK 제약은 V1 baseline이 prod 덤프를 옮겨 올 때 `SESSION` 하나뿐이었고, 그 뒤 이 목록만 자라
 * dev·prod에서는 규정 문서 업로드가 전부 제약 위반으로 터지고 있었다 — `ddl-auto: update`는 기존
 * CHECK 제약을 넓히지 않고, `test` 프로필은 Flyway가 꺼져 있어 H2 스키마를 Hibernate가 현재 enum
 * 으로 만들어 주므로 그 어긋남이 테스트에 보이지 않았다. `ShareTargetType`이 같은 자리에서 먼저
 * 겪었고(V6) 그 주석이 이 규칙을 적어 두었는데도 여기까지 닿지 않았으므로, 이제는
 * `FlywayMigrationValidateTest.checkConstraintsMatchTheirEnums`가 대조한다.
 *
 * 대상이 늘 때 하는 일은 여기 한 줄과 표준코드 시트 한 줄, 그리고 **제약을 넓히는 마이그레이션
 * 파일 하나**다.
 */
@Getter
@RequiredArgsConstructor
public enum FileTargetType {

    /** 학술 회차 출석 인증사진 (#137). 키는 academic-programs/{활동}/sessions/{회차}/{uuid}.{ext} */
    SESSION("academic-programs/"),

    /*
     * 규정 도우미 코퍼스의 원본 파일 (#402 · ADR-0029). 키는 rag-documents/{문서}/{uuid}.{ext}.
     *
     * 청크에서 원문을 복원할 수 없으므로(해설을 뺐고 overlap이 겹치며 장 헤더를 덧붙였다)
     * 재색인의 재료가 이 원본이다. 접근 판정은 RAG_DOCUMENT_MANAGE이고 그 판정은 assistant
     * 도메인이 한다 — 이 도메인은 파일이 버킷의 어디에 있는지만 안다.
     */
    RAG_DOCUMENT("rag-documents/");

    /** 이 대상의 오브젝트 키 접두사 */
    private final String objectKeyPrefix;
}
