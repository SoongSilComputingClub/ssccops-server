package org.sscc.ssccopsserver.domain.form.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.sscc.ssccopsserver.domain.form.code.QuestionItemType;
import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.code.error.FormErrorCode;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseHistoryRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;
import org.sscc.ssccopsserver.global.config.JpaAuditingConfig;
import org.sscc.ssccopsserver.global.config.JsonFormatMapperConfig;
import org.sscc.ssccopsserver.support.MemberFixture;

/*
 * JSONB 두 컬럼(form.qitem_cpst_cn · form_rspns_hstry.rspns_cn)의 영속화 왕복 검증.
 *
 * 이 테스트가 이 이슈의 핵심이다. 운영은 PostgreSQL(jsonb)인데 테스트는 H2라 두 엔진의
 * JSON 처리가 갈리면 "테스트는 통과하는데 배포하면 깨지는" 상태가 된다. 그래서 매핑이
 * 붙었는지만 보지 않고, 중첩 배열·선택적 필드·Map까지 넣어 구조가 그대로 돌아오는지 본다.
 *
 * @DataJpaTest는 일반 @Configuration을 걸러내므로 JPA Auditing(crt_dt·mdfcn_dt)과
 * JSON FormatMapper를 명시적으로 들여온다 — 둘 다 없으면 NOT NULL 위반과 기본 매퍼로
 * 조용히 다른 것을 검증하게 된다.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({JpaAuditingConfig.class, JsonFormatMapperConfig.class})
class FormJsonPersistenceTest {

    @Autowired private TestEntityManager entityManager;
    @Autowired private FormRepository formRepository;
    @Autowired private FormResponseHistoryRepository formResponseHistoryRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;

    private MemberEntity member(String studentNumber) {
        return MemberFixture.save(
                memberRepository,
                memberGradeRepository,
                memberStatusRepository,
                UUID.randomUUID(),
                studentNumber,
                "홍길동",
                studentNumber + "@soongsil.ac.kr");
    }

    /*
     * 문항 구성 표본. 유형별 전용 속성이 서로 다른 문항을 섞어 둔 것은, 쓰지 않는 속성이
     * NULL로 빠졌다가 그대로 NULL로 돌아오는지까지 봐야 하기 때문이다.
     */
    private QuestionCompositionContent sampleComposition() {
        QuestionCompositionContent.QuestionItem shortText =
                new QuestionCompositionContent.QuestionItem(
                        "q1",
                        "이름",
                        QuestionItemType.SHORT_TEXT,
                        true,
                        0,
                        List.of(),
                        null,
                        "^[가-힣]{2,5}$",
                        "한글 이름",
                        "한글 2~5자로 입력해주세요.",
                        null);

        QuestionCompositionContent.QuestionItem singleChoice =
                new QuestionCompositionContent.QuestionItem(
                        "q2",
                        "지원 분야",
                        QuestionItemType.SINGLE_CHOICE,
                        true,
                        0,
                        List.of("백엔드", "프론트엔드"),
                        Map.of("백엔드", 1, "프론트엔드", 1),
                        null,
                        null,
                        null,
                        null);

        QuestionCompositionContent.QuestionItem multiChoice =
                new QuestionCompositionContent.QuestionItem(
                        "q3",
                        "관심 스택",
                        QuestionItemType.MULTI_CHOICE,
                        false,
                        1,
                        List.of("Spring", "React", "Kotlin"),
                        null,
                        null,
                        null,
                        null,
                        3);

        return new QuestionCompositionContent(
                List.of(
                        new QuestionCompositionContent.Page("기본 정보", "지원자 정보를 입력해주세요."),
                        new QuestionCompositionContent.Page("상세", "관심 분야를 골라주세요.")),
                List.of(shortText, singleChoice, multiChoice));
    }

    private FormEntity saveForm(MemberEntity creator) {
        return formRepository.saveAndFlush(
                FormEntity.create(
                        creator,
                        "2026 신규모집 지원서",
                        sampleComposition(),
                        Instant.parse("2026-03-01T00:00:00Z"),
                        Instant.parse("2026-03-31T00:00:00Z")));
    }

    @Test
    void keepsNestedQuestionCompositionStructureAcrossPersistence() {
        FormEntity saved = saveForm(member("20260001"));
        entityManager.clear();

        QuestionCompositionContent reloaded =
                formRepository.findById(saved.getId()).orElseThrow().getQuestionComposition();

        assertThat(reloaded).isEqualTo(sampleComposition());
        assertThat(reloaded.qitems()).hasSize(3);
        assertThat(reloaded.qitems().get(1).branchMap()).containsEntry("백엔드", 1);
        assertThat(reloaded.qitems().get(2).maxSlctCnt()).isEqualTo(3);
    }

    // 쓰지 않는 유형 전용 속성은 NULL로 나갔다가 NULL로 돌아와야 한다 (기본값으로 채워지면 안 된다)
    @Test
    void keepsOptionalQuestionFieldsNull() {
        FormEntity saved = saveForm(member("20260002"));
        entityManager.clear();

        QuestionCompositionContent.QuestionItem singleChoice =
                formRepository
                        .findById(saved.getId())
                        .orElseThrow()
                        .getQuestionComposition()
                        .qitems()
                        .get(1);

        assertThat(singleChoice.ptrnCn()).isNull();
        assertThat(singleChoice.maxSlctCnt()).isNull();
    }

    /*
     * 컬럼에 실제로 들어간 값이 JSON 문서인지 확인한다. 매핑이 조용히 문자열 직렬화로
     * 떨어져도 왕복 테스트는 통과해 버리므로, 원문에 JSON 키가 보이는지까지 본다.
     * H2의 JSON 타입은 드라이버가 byte[]로 돌려주기도 해서 두 경우를 모두 받는다.
     */
    @Test
    void storesCompositionAsJsonDocumentNotAnOpaqueBlob() {
        FormEntity saved = saveForm(member("20260003"));

        Object raw =
                entityManager
                        .getEntityManager()
                        .createNativeQuery("select qitem_cpst_cn from form where form_id = :id")
                        .setParameter("id", saved.getId())
                        .getSingleResult();

        assertThat(asText(raw)).contains("\"qitemId\"").contains("\"SHORT_TEXT\"");
    }

    /*
     * ddl-auto가 두 JSON 컬럼을 실제로 JSON 계열 타입으로 만드는지 확인한다.
     *
     * @JdbcTypeCode(SqlTypes.JSON)이 방언을 못 찾으면 조용히 VARCHAR/CLOB으로 떨어지는데,
     * 그래도 왕복 테스트는 통과한다 — 대신 운영(PostgreSQL)에서만 jsonb 연산자를 못 쓰게 된다.
     * H2는 JSON, PostgreSQL은 JSONB라 타입명을 못 박지 않고 JSON 계열인지만 본다.
     */
    @Test
    void createsJsonColumnsForBothContentFields() {
        assertThat(columnTypeOf("FORM", "QITEM_CPST_CN")).containsIgnoringCase("JSON");
        assertThat(columnTypeOf("FORM_RSPNS_HSTRY", "RSPNS_CN")).containsIgnoringCase("JSON");
    }

    private String columnTypeOf(String tableName, String columnName) {
        return String.valueOf(
                entityManager
                        .getEntityManager()
                        .createNativeQuery(
                                "select data_type from information_schema.columns"
                                        + " where upper(table_name) = :table"
                                        + " and upper(column_name) = :column")
                        .setParameter("table", tableName)
                        .setParameter("column", columnName)
                        .getSingleResult());
    }

    /*
     * 응답 내용. 다중선택만 배열이고 나머지는 문자열이라는 계약이 왕복에서 유지되는지 본다 —
     * 배열이 문자열로 뭉개지면 응답 조회(#37)가 선택지를 하나로 붙여 보여주게 된다.
     */
    @Test
    void keepsResponseAnswerTypesAcrossPersistence() {
        MemberEntity responder = member("20260004");
        FormEntity form = saveForm(responder);

        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("q1", "홍길동");
        answers.put("q2", "백엔드");
        answers.put("q3", List.of("Spring", "Kotlin"));

        FormResponseHistoryEntity saved =
                formResponseHistoryRepository.saveAndFlush(
                        FormResponseHistoryEntity.createSubmitted(
                                form,
                                responder,
                                ResponseContent.of(answers),
                                Instant.parse("2026-03-10T12:00:00Z")));
        entityManager.clear();

        FormResponseHistoryEntity reloaded =
                formResponseHistoryRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getContent().answers())
                .containsEntry("q1", "홍길동")
                .containsEntry("q3", List.of("Spring", "Kotlin"));
        assertThat(reloaded.getContent()).isEqualTo(ResponseContent.of(answers));
        assertThat(reloaded.getStatus()).isEqualTo(ResponseStatus.SUBMITTED);
    }

    // rspns_cn은 record 껍데기 없이 답변 Map 자체가 JSON 객체로 들어가야 한다 (웹 계약)
    @Test
    void storesResponseContentWithoutWrapperKey() {
        MemberEntity responder = member("20260005");
        FormEntity form = saveForm(responder);

        FormResponseHistoryEntity saved =
                formResponseHistoryRepository.saveAndFlush(
                        FormResponseHistoryEntity.createSubmitted(
                                form,
                                responder,
                                ResponseContent.of(Map.of("q1", "홍길동")),
                                Instant.parse("2026-03-10T12:00:00Z")));

        Object raw =
                entityManager
                        .getEntityManager()
                        .createNativeQuery(
                                "select rspns_cn from form_rspns_hstry where form_rspns_id = :id")
                        .setParameter("id", saved.getId())
                        .getSingleResult();

        assertThat(asText(raw)).contains("\"q1\"").doesNotContain("answers");
    }

    /*
     * 임시저장(#36)은 제출 일시 없이 저장된다 — ssccops #64에서 sbmsn_dt를 nullable로 정한
     * 이유가 이 상태다. 내용이 비어 있어도 NULL이 아니라 빈 객체로 들어간다.
     */
    @Test
    void savesDraftResponseWithoutSubmissionTimestamp() {
        MemberEntity responder = member("20260006");
        FormEntity form = saveForm(responder);

        FormResponseHistoryEntity saved =
                formResponseHistoryRepository.saveAndFlush(
                        FormResponseHistoryEntity.createDraft(form, responder, null));
        entityManager.clear();

        FormResponseHistoryEntity reloaded =
                formResponseHistoryRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getStatus()).isEqualTo(ResponseStatus.DRAFT);
        assertThat(reloaded.getSubmittedAt()).isNull();
        assertThat(reloaded.getContent().answers()).isEmpty();
    }

    /*
     * JSONB는 JSON 문법만 보장할 뿐 우리 구조까지 보장하지 않는다. 손으로 고친 행이나 옛 구조로
     * 저장된 행을 읽으면 Jackson 예외가 그대로 올라가 500이 되는데, 그러면 프론트는 "서버가
     * 고장났다"는 말만 듣는다. 실제로 깨진 것은 이 폼 한 건이므로 도메인 오류로 내려야 한다.
     *
     * pages를 배열이 아닌 문자열로 바꿔 문법은 멀쩡하고 구조만 어긋난 행을 만든다.
     */
    @Test
    void translatesMalformedStoredJsonIntoDomainError() {
        FormEntity saved = saveForm(member("20260007"));

        entityManager
                .getEntityManager()
                .createNativeQuery(
                        "update form set qitem_cpst_cn = :json format json where form_id = :id")
                .setParameter("json", "{\"pages\":\"두 페이지\",\"qitems\":[]}")
                .setParameter("id", saved.getId())
                .executeUpdate();
        entityManager.clear();

        assertThatThrownBy(() -> formRepository.findById(saved.getId()).orElseThrow())
                .isInstanceOf(GeneralException.class)
                .extracting(thrown -> ((GeneralException) thrown).getErrorCode())
                .isEqualTo(FormErrorCode.FORM_CONTENT_MALFORMED);
    }

    private String asText(Object raw) {
        return raw instanceof byte[] bytes
                ? new String(bytes, StandardCharsets.UTF_8)
                : String.valueOf(raw);
    }
}
