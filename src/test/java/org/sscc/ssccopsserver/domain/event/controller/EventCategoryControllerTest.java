package org.sscc.ssccopsserver.domain.event.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

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
import org.sscc.ssccopsserver.domain.event.entity.EventClassificationEntity;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.event.repository.EventClassificationRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.AuthorityRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleClassificationRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.domain.member.repository.RoleAuthorityRelationRepository;
import org.sscc.ssccopsserver.support.AuthorityFixture;
import org.sscc.ssccopsserver.support.MemberFixture;

/*
 * 행사 분류 관리 API(ssccops#140) 통합 검증 (RoleClassificationControllerTest 선례).
 *
 * 확인의 중심은 **"분류가 사라져도 행사가 갈 곳을 잃지 않는다"**이다 — 사용 중인 분류는
 * 지워지지 않고, PK인 코드는 바뀌지 않는다. 역할 분류와 달리 조회도 EVENT_MANAGE가 필요하다
 * (클래스 레벨) — 행사 분류를 쓰는 화면이 행사 관리 하나뿐이라서다.
 *
 * 요청 주체를 요청마다 바꿔야 해서 JwtDecoder 스텁이 토큰 문자열을 그대로 sub로 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EventCategoryControllerTest.StubJwtDecoderConfig.class)
@Transactional
class EventCategoryControllerTest {

    private static final String CATEGORIES = "/v1/event-categories";

    @Autowired private MockMvc mockMvc;
    @Autowired private EntityManager entityManager;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private AuthorityRepository authorityRepository;
    @Autowired private RoleAuthorityRelationRepository roleAuthorityRelationRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private EventClassificationRepository eventClassificationRepository;

    private UUID managerToken;
    private UUID outsiderToken;
    private MemberEntity manager;

    @BeforeEach
    void setUp() {
        managerToken = UUID.randomUUID();
        manager = saveMember(managerToken, "20260101", "행사운영자");
        grant(manager, AuthorityCode.EVENT_MANAGE);

        // EVENT_MANAGE가 없는 회원. '다른 권한만' 가진 쪽이어야 403이 권한 때문이라는 것이 드러난다
        outsiderToken = UUID.randomUUID();
        MemberEntity outsider = saveMember(outsiderToken, "20260102", "업무담당");
        grant(outsider, AuthorityCode.WORK_MANAGE);
    }

    /* ── 목록 ─────────────────────────────────────────────── */

    /*
     * 시드 4종이 indct_seqno 순으로 내려온다. 순서를 못 박는 것은 화면이 이 배열을 그대로
     * 그리기 때문이다. 갓 시드된 분류를 쓰는 행사는 없으므로 eventCount는 전부 0이다.
     */
    @Test
    void listsSeededClassificationsInDisplayOrder() throws Exception {
        mockMvc.perform(authorized(get(CATEGORIES), managerToken))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data[*].eventClsfCd")
                                .value(contains("RECRUIT", "SEMINAR", "PROJECT", "EVENT")))
                .andExpect(jsonPath("$.data[0].eventClsfNm").value("모집"))
                .andExpect(jsonPath("$.data[0].indctSeqno").value(1))
                .andExpect(jsonPath("$.data[0].eventCount").value(0));
    }

    @Test
    void listCountsEventsUsingEachClassification() throws Exception {
        saveEventUsing("RECRUIT");
        saveEventUsing("RECRUIT");
        flushAndClear();

        mockMvc.perform(authorized(get(CATEGORIES), managerToken))
                .andExpect(jsonPath("$.data[0].eventClsfCd").value("RECRUIT"))
                .andExpect(jsonPath("$.data[0].eventCount").value(2))
                .andExpect(jsonPath("$.data[1].eventCount").value(0));
    }

    /* ── 생성 ─────────────────────────────────────────────── */

    @Test
    void createdClassificationAppearsInList() throws Exception {
        mockMvc.perform(
                        authorized(post(CATEGORIES), managerToken)
                                .content(createBody("HOMECOMING", "홈커밍", 5)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.eventClsfCd").value("HOMECOMING"))
                .andExpect(jsonPath("$.data.eventClsfNm").value("홈커밍"))
                .andExpect(jsonPath("$.data.indctSeqno").value(5))
                // 갓 만든 분류를 쓰는 행사는 있을 수 없다
                .andExpect(jsonPath("$.data.eventCount").value(0));

        mockMvc.perform(authorized(get(CATEGORIES), managerToken))
                .andExpect(jsonPath("$.data[4].eventClsfCd").value("HOMECOMING"));
    }

    /** indctSeqno를 생략해도 만들어진다 — 순서를 정하지 않았다고 생성이 막힐 이유가 없다 */
    @Test
    void createsClassificationWithDefaultDisplayOrder() throws Exception {
        mockMvc.perform(
                        authorized(post(CATEGORIES), managerToken)
                                .content(
                                        """
                                        {"eventClsfCd": "HOMECOMING", "eventClsfNm": "홈커밍"}
                                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.indctSeqno").value(99));
    }

    @Test
    void rejectsDuplicatedClassificationCode() throws Exception {
        mockMvc.perform(
                        authorized(post(CATEGORIES), managerToken)
                                .content(createBody("RECRUIT", "모집 사칭", 9)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_CLASSIFICATION_CODE_DUPLICATED"));
    }

    // 코드값은 표준코드 시트에 등재되는 값이라 표기(UPPER_SNAKE_CASE)를 서버가 못 박는다
    @Test
    void rejectsLowercaseClassificationCode() throws Exception {
        mockMvc.perform(
                        authorized(post(CATEGORIES), managerToken)
                                .content(createBody("homecoming", "홈커밍", 5)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /* ── 수정 ─────────────────────────────────────────────── */

    @Test
    void updatesNameAndDisplayOrder() throws Exception {
        mockMvc.perform(
                        authorized(patch(CATEGORIES + "/SEMINAR"), managerToken)
                                .content("{\"eventClsfNm\": \"정기 세미나\", \"indctSeqno\": 9}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eventClsfCd").value("SEMINAR"))
                .andExpect(jsonPath("$.data.eventClsfNm").value("정기 세미나"))
                .andExpect(jsonPath("$.data.indctSeqno").value(9));
    }

    // indctSeqno가 null이면 현재 값을 유지한다 — 이름만 고치는 화면이 순번까지 들지 않아도 된다
    @Test
    void updateWithoutDisplayOrderKeepsCurrentOrder() throws Exception {
        mockMvc.perform(
                        authorized(patch(CATEGORIES + "/SEMINAR"), managerToken)
                                .content("{\"eventClsfNm\": \"정기 세미나\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.indctSeqno").value(2));
    }

    @Test
    void updateUnknownClassificationReturns404() throws Exception {
        mockMvc.perform(
                        authorized(patch(CATEGORIES + "/UNKNOWN"), managerToken)
                                .content("{\"eventClsfNm\": \"이름\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_CLASSIFICATION_NOT_FOUND"));
    }

    /* ── 삭제 ─────────────────────────────────────────────── */

    @Test
    void deletesUnusedClassification() throws Exception {
        mockMvc.perform(authorized(delete(CATEGORIES + "/PROJECT"), managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        flushAndClear();
        assertThat(eventClassificationRepository.findById("PROJECT")).isEmpty();
    }

    /*
     * event.event_clsf_cd가 NOT NULL FK다(D13) — 사용 중인 분류를 지우면 행사가 갈 곳을 잃는다.
     * 행사를 다른 분류로 먼저 옮기게 해서 무엇이 어디로 가는지 화면에서 보이게 한다.
     */
    @Test
    void deleteClassificationInUseReturns409() throws Exception {
        saveEventUsing("PROJECT");
        flushAndClear();

        mockMvc.perform(authorized(delete(CATEGORIES + "/PROJECT"), managerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_CLASSIFICATION_IN_USE"));
    }

    @Test
    void deleteUnknownClassificationReturns404() throws Exception {
        mockMvc.perform(authorized(delete(CATEGORIES + "/UNKNOWN"), managerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_CLASSIFICATION_NOT_FOUND"));
    }

    /* ── 인가 ─────────────────────────────────────────────── */

    // 클래스 레벨 EVENT_MANAGE다 — 역할 분류(#80)와 달리 조회도 예외가 아니다
    @Test
    void requestsWithoutEventManageAreForbidden() throws Exception {
        mockMvc.perform(authorized(get(CATEGORIES), outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(
                        authorized(post(CATEGORIES), outsiderToken)
                                .content(createBody("HOMECOMING", "홈커밍", 5)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(
                        authorized(patch(CATEGORIES + "/SEMINAR"), outsiderToken)
                                .content("{\"eventClsfNm\": \"이름\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(authorized(delete(CATEGORIES + "/PROJECT"), outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /* ── 헬퍼 ─────────────────────────────────────────────── */

    private void saveEventUsing(String classificationCode) {
        EventClassificationEntity classification =
                eventClassificationRepository.findById(classificationCode).orElseThrow();
        eventRepository.save(
                EventEntity.create(
                        classification,
                        manager,
                        "분류 사용 행사",
                        "# 본문",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private MemberEntity saveMember(UUID authUserId, String studentNumber, String name) {
        return MemberFixture.save(
                memberRepository,
                memberGradeRepository,
                memberStatusRepository,
                authUserId,
                studentNumber,
                name,
                studentNumber + "@sscc.org");
    }

    private void grant(MemberEntity member, AuthorityCode authority) {
        AuthorityFixture.grant(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                authorityRepository,
                roleAuthorityRelationRepository,
                member,
                authority);
    }

    private static String createBody(String code, String name, int displayOrder) {
        return "{\"eventClsfCd\": \"%s\", \"eventClsfNm\": \"%s\", \"indctSeqno\": %d}"
                .formatted(code, name, displayOrder);
    }

    private static MockHttpServletRequestBuilder authorized(
            MockHttpServletRequestBuilder builder, UUID authUserId) {
        return builder.header("Authorization", "Bearer " + authUserId)
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
                            .subject(token)
                            .claim("email", token + "@sscc.org")
                            .issuedAt(Instant.now())
                            .expiresAt(Instant.now().plusSeconds(60))
                            .build();
        }
    }
}
