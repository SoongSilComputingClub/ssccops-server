package org.sscc.ssccopsserver.domain.form.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormLabelEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormLabelRelationEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
import org.sscc.ssccopsserver.domain.form.repository.FormLabelRelationRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormLabelRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.support.MemberFixture;

/*
 * 폼 라벨 관리·지정 API (#34).
 *
 * 인증 필터체인을 그대로 태우기 위해 JwtDecoder만 고정 Jwt를 반환하도록 대체하고, 그 sub에
 * 연결된 회원을 미리 만든다 — @CurrentMember가 미가입 주체를 403으로 끊기 때문이다.
 *
 * 확인의 중심은 "라벨은 지우지 않고 use_yn만 내린다"가 지정 관계에 어떻게 다르게 적용되는가다.
 * 비활성 라벨은 새로 달 수 없고 활성 목록에서 빠지지만, 이미 달린 연결은 그대로 남아야 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FormLabelControllerTest.StubJwtDecoderConfig.class)
@Transactional
class FormLabelControllerTest {

    private static final UUID AUTH_USER_ID = UUID.randomUUID();
    private static final String EMAIL = "20260101@soongsil.ac.kr";

    @Autowired private MockMvc mockMvc;
    @Autowired private EntityManager entityManager;
    @Autowired private FormRepository formRepository;
    @Autowired private FormLabelRepository formLabelRepository;
    @Autowired private FormLabelRelationRepository formLabelRelationRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;

    private MemberEntity operator;
    private FormEntity form;

    @BeforeEach
    void setUp() {
        operator =
                MemberFixture.save(
                        memberRepository,
                        memberGradeRepository,
                        memberStatusRepository,
                        AUTH_USER_ID,
                        "20260101",
                        "홍길동",
                        EMAIL);
        form = saveForm("2026 신규모집 지원서");
    }

    // ------------------------------------------------------------------ 라벨 생성

    @Test
    void createsActiveLabelWithZeroUsage() throws Exception {
        mockMvc.perform(authorized(post("/v1/form-labels")).content(createBody("2026-2 신규모집")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.lblNm").value("2026-2 신규모집"))
                // 만들자마자 비활성인 라벨은 쓸모가 없으므로 생성은 항상 활성이다
                .andExpect(jsonPath("$.data.useYn").value(true))
                .andExpect(jsonPath("$.data.usageCount").value(0));

        assertThat(formLabelRepository.findByName("2026-2 신규모집")).isPresent();
    }

    @Test
    void rejectsDuplicatedLabelName() throws Exception {
        saveLabel("신규모집");

        mockMvc.perform(authorized(post("/v1/form-labels")).content(createBody("신규모집")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FORM_LABEL_NAME_DUPLICATED"));

        assertThat(formLabelRepository.count()).isEqualTo(1);
    }

    // lbl_nm은 V50이다. DB가 거절하기 전에 400으로 알려야 화면이 어느 값이 문제인지 안내할 수 있다
    @Test
    void rejectsLabelNameLongerThanFiftyCharacters() throws Exception {
        mockMvc.perform(authorized(post("/v1/form-labels")).content(createBody("가".repeat(51))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(formLabelRepository.count()).isZero();
    }

    @Test
    void rejectsBlankLabelName() throws Exception {
        mockMvc.perform(authorized(post("/v1/form-labels")).content(createBody("   ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ------------------------------------------------------------------ 목록·사용 여부

    /*
     * 비활성으로 내린 라벨은 지정·필터 화면이 보는 목록(?useYn=true)에서 빠지지만, 관리 화면이
     * 보는 전체 목록에는 남아 있어야 한다 — 취소선으로 보여주고 다시 켤 수 있어야 하기 때문이다.
     */
    @Test
    void deactivatedLabelDropsOutOfActiveListButStaysInFullList() throws Exception {
        FormLabelEntity recruiting = saveLabel("신규모집");
        saveLabel("행사");

        mockMvc.perform(
                        authorized(patch("/v1/form-labels/" + recruiting.getId()))
                                .content("{\"useYn\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.useYn").value(false));

        mockMvc.perform(authorized(get("/v1/form-labels")).param("useYn", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].lblNm").value("행사"));

        mockMvc.perform(authorized(get("/v1/form-labels")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[*].lblNm", containsInAnyOrder("신규모집", "행사")));
    }

    // 비활성으로 내려도 이미 걸린 form_lbl_rel은 한 행도 사라지지 않는다 — 과거 분류 이력이다
    @Test
    void deactivationKeepsExistingRelations() throws Exception {
        FormLabelEntity recruiting = saveLabel("신규모집");
        assign(form, recruiting);

        mockMvc.perform(
                        authorized(patch("/v1/form-labels/" + recruiting.getId()))
                                .content("{\"useYn\": false}"))
                .andExpect(status().isOk())
                // 관리 화면이 "사용 중인 폼 N건"을 보고 비활성화 여부를 판단하므로 건수는 그대로다
                .andExpect(jsonPath("$.data.usageCount").value(1));

        flushAndClear();
        assertThat(formLabelRelationRepository.count()).isEqualTo(1);
    }

    @Test
    void unknownLabelToggleReturnsNotFound() throws Exception {
        mockMvc.perform(authorized(patch("/v1/form-labels/999999")).content("{\"useYn\": false}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FORM_LABEL_NOT_FOUND"));
    }

    @Test
    void missingUseYnIsRejected() throws Exception {
        FormLabelEntity recruiting = saveLabel("신규모집");

        mockMvc.perform(authorized(patch("/v1/form-labels/" + recruiting.getId())).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // 라벨마다 세지 않고 집계 한 번으로 받아오지만, 값 자체는 실제 폼 수와 같아야 한다
    @Test
    void usageCountMatchesTheNumberOfFormsUsingTheLabel() throws Exception {
        FormLabelEntity recruiting = saveLabel("신규모집");
        FormLabelEntity event = saveLabel("행사");

        assign(form, recruiting);
        assign(saveForm("2026 회원연장 신청서"), recruiting);
        assign(saveForm("가을 행사 신청서"), event);
        saveLabel("스터디");

        mockMvc.perform(authorized(get("/v1/form-labels")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3)))
                // 이름 오름차순: 스터디 < 신규모집 < 행사
                .andExpect(jsonPath("$.data[0].lblNm").value("스터디"))
                // 한 번도 쓰이지 않은 라벨은 집계 결과에 없다 — 그것을 0건으로 읽어야 한다
                .andExpect(jsonPath("$.data[0].usageCount").value(0))
                .andExpect(jsonPath("$.data[1].lblNm").value("신규모집"))
                .andExpect(jsonPath("$.data[1].usageCount").value(2))
                .andExpect(jsonPath("$.data[2].lblNm").value("행사"))
                .andExpect(jsonPath("$.data[2].usageCount").value(1));
    }

    // ------------------------------------------------------------------ 지정 교체

    /*
     * 교체의 세 갈래를 한 번에 본다 — 요청에 없는 것은 해제, 새로 온 것은 추가, 유지되는 것은
     * 손대지 않는다. 유지된 연결이 같은 form_lbl_rel_id와 같은 crt_dt를 갖는지가 핵심이다.
     * 지우고 다시 넣는 구현이면 식별자가 바뀌어 여기서 걸린다.
     */
    @Test
    void replacesAssignmentsAndPreservesCreatedAtOfKeptRelations() throws Exception {
        FormLabelEntity recruiting = saveLabel("신규모집");
        FormLabelEntity event = saveLabel("행사");
        FormLabelEntity study = saveLabel("스터디");

        Long keptRelationId = assign(form, recruiting).getId();
        assign(form, event);

        /*
         * 비교할 crt_dt를 메모리 값이 아니라 DB에서 다시 읽는다 — Instant는 나노초까지 갖지만
         * 저장된 값은 마이크로초라, 그대로 비교하면 보존 여부와 무관하게 어긋난다.
         */
        flushAndClear();
        Instant keptCreatedAt =
                formLabelRelationRepository.findById(keptRelationId).orElseThrow().getCreatedAt();

        // 신규모집은 유지, 행사는 해제, 스터디는 추가
        mockMvc.perform(assignLabels(form, recruiting, study))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[*].lblNm", containsInAnyOrder("신규모집", "스터디")));

        flushAndClear();
        List<FormLabelRelationEntity> relations = formLabelRelationRepository.findAllByForm(form);
        assertThat(relations).hasSize(2);

        FormLabelRelationEntity keptAfter =
                relations.stream()
                        .filter(relation -> relation.getLabel().getId().equals(recruiting.getId()))
                        .findFirst()
                        .orElseThrow();
        assertThat(keptAfter.getId()).isEqualTo(keptRelationId);
        assertThat(keptAfter.getCreatedAt()).isEqualTo(keptCreatedAt);
    }

    // 빈 배열은 오류가 아니라 "전부 해제"다
    @Test
    void emptyLabelIdsClearsEveryAssignment() throws Exception {
        assign(form, saveLabel("신규모집"));
        assign(form, saveLabel("행사"));

        mockMvc.perform(assignLabels(form))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));

        flushAndClear();
        assertThat(formLabelRelationRepository.findAllByForm(form)).isEmpty();
    }

    /*
     * 같은 요청을 두 번 보내도 결과가 같아야 한다. 중복 행이 생기지 않는 것은 물론이고,
     * (form_id, form_lbl_id) UNIQUE에 걸려 409가 나서도 안 된다 — 화면의 저장 버튼은
     * 두 번 눌릴 수 있고, 두 번째 저장은 아무것도 바꾸지 않는 정상 동작이다.
     */
    @Test
    void repeatingTheSameAssignmentRequestIsIdempotent() throws Exception {
        FormLabelEntity recruiting = saveLabel("신규모집");
        FormLabelEntity event = saveLabel("행사");

        mockMvc.perform(assignLabels(form, recruiting, event)).andExpect(status().isOk());
        flushAndClear();
        List<Long> firstRelationIds = relationIdsOf(form);

        mockMvc.perform(assignLabels(form, recruiting, event))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)));

        flushAndClear();
        // 행이 늘지도, 지웠다 다시 생기지도 않았다
        assertThat(relationIdsOf(form)).isEqualTo(firstRelationIds);
        assertThat(formLabelRelationRepository.count()).isEqualTo(2);
    }

    // 같은 라벨을 두 번 실어 보내도 한 번으로 본다 — 화면 실수가 UNIQUE 위반으로 번지면 안 된다
    @Test
    void duplicatedLabelIdInRequestCreatesOneRelation() throws Exception {
        FormLabelEntity recruiting = saveLabel("신규모집");

        mockMvc.perform(assignLabels(form, recruiting, recruiting))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)));

        flushAndClear();
        assertThat(formLabelRelationRepository.findAllByForm(form)).hasSize(1);
    }

    /*
     * 이 이슈의 핵심 구분. 편집 화면은 "현재 선택된 칩 전체"를 그대로 보내므로, 이미 지정된
     * 비활성 라벨이 요청에 다시 실려 오는 것이 정상 경로다. 이것을 400으로 막으면 비활성 라벨이
     * 붙은 폼은 라벨을 하나 더 다는 것조차 못 하게 된다.
     */
    @Test
    void alreadyAssignedInactiveLabelStaysAssignedWhenResent() throws Exception {
        FormLabelEntity retired = saveLabel("2025 신규모집");
        FormLabelEntity study = saveLabel("스터디");
        assign(form, retired);
        retired.changeActive(false);
        flushAndClear();

        // 비활성 라벨을 그대로 실어 보내면서 활성 라벨을 하나 더 단다
        mockMvc.perform(assignLabels(form, retired, study))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[*].lblNm", containsInAnyOrder("2025 신규모집", "스터디")));

        flushAndClear();
        assertThat(formLabelRelationRepository.findAllByForm(form)).hasSize(2);
    }

    // 반대로 비활성 라벨을 '새로' 다는 것은 막는다. 실패한 요청은 기존 지정도 건드리지 않는다
    @Test
    void newlyAddedInactiveLabelIsRejected() throws Exception {
        FormLabelEntity recruiting = saveLabel("신규모집");
        FormLabelEntity retired = saveLabel("2025 신규모집");
        assign(form, recruiting);
        retired.changeActive(false);
        flushAndClear();

        mockMvc.perform(assignLabels(form, recruiting, retired))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FORM_LABEL_NOT_USABLE"));

        flushAndClear();
        assertThat(formLabelRelationRepository.findAllByForm(form)).hasSize(1);
    }

    /*
     * 폼이 없을 때와 라벨이 없을 때가 같은 요청에서 둘 다 404로 나오므로 코드 문자열이 갈려야 한다 —
     * 폼 쪽은 #31이 정한 공통 NOT_FOUND를 그대로 쓰고, 라벨 쪽만 전용 코드를 새로 둔다.
     */
    @Test
    void assigningToUnknownFormReturnsNotFound() throws Exception {
        FormLabelEntity recruiting = saveLabel("신규모집");

        mockMvc.perform(
                        authorized(put("/v1/forms/999999/labels"))
                                .content("{\"labelIds\": [%d]}".formatted(recruiting.getId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void assigningUnknownLabelReturnsNotFound() throws Exception {
        mockMvc.perform(
                        authorized(put("/v1/forms/" + form.getId() + "/labels"))
                                .content("{\"labelIds\": [999999]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FORM_LABEL_NOT_FOUND"));
    }

    // labelIds 자체가 빠지면 "건드리지 마라"인지 "전부 지워라"인지 알 수 없다
    @Test
    void missingLabelIdsIsRejected() throws Exception {
        mockMvc.perform(authorized(put("/v1/forms/" + form.getId() + "/labels")).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // 토큰 없는 호출은 인증에서 끊긴다
    @Test
    void requestWithoutTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/v1/form-labels")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ 헬퍼

    private FormEntity saveForm(String title) {
        return formRepository.saveAndFlush(
                FormEntity.create(
                        operator,
                        title,
                        new QuestionCompositionContent(List.of(), List.of()),
                        null,
                        null));
    }

    private FormLabelEntity saveLabel(String name) {
        return formLabelRepository.saveAndFlush(FormLabelEntity.create(name));
    }

    private FormLabelRelationEntity assign(FormEntity target, FormLabelEntity label) {
        return formLabelRelationRepository.saveAndFlush(
                FormLabelRelationEntity.create(target, label));
    }

    private List<Long> relationIdsOf(FormEntity target) {
        return formLabelRelationRepository.findAllByForm(target).stream()
                .map(FormLabelRelationEntity::getId)
                .sorted()
                .toList();
    }

    /*
     * 테스트가 한 트랜잭션 안에서 돌아 서비스와 영속성 컨텍스트를 공유한다. 지정 교체가 정말
     * DB까지 반영됐는지 보려면 1차 캐시를 비우고 다시 읽어야 한다.
     */
    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private MockHttpServletRequestBuilder assignLabels(
            FormEntity target, FormLabelEntity... labels) {
        String ids =
                Arrays.stream(labels)
                        .map(label -> String.valueOf(label.getId()))
                        .collect(Collectors.joining(", "));
        return authorized(put("/v1/forms/" + target.getId() + "/labels"))
                .content("{\"labelIds\": [%s]}".formatted(ids));
    }

    private static String createBody(String name) {
        return "{\"lblNm\": \"%s\"}".formatted(name);
    }

    private static MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer any-token")
                .contentType(MediaType.APPLICATION_JSON);
    }

    @TestConfiguration
    static class StubJwtDecoderConfig {

        @Bean
        @Primary
        JwtDecoder jwtDecoder() {
            return token ->
                    Jwt.withTokenValue(token)
                            .header("alg", "none")
                            .subject(AUTH_USER_ID.toString())
                            .claim("email", EMAIL)
                            .claim("user_metadata", Map.of("full_name", "홍길동"))
                            .claim("app_metadata", Map.of("provider", "google"))
                            .issuedAt(Instant.now())
                            .expiresAt(Instant.now().plusSeconds(60))
                            .build();
        }
    }
}
