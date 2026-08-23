package org.sscc.ssccopsserver.domain.academicprogram.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
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
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramTypeRepository;
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
 * 학술 활동 유형 코드테이블 API (#130).
 *
 * 요청 주체를 요청마다 바꿔야 해서(권한 있는 학술국장 · 없는 회원) JwtDecoder 스텁이 토큰
 * 문자열을 그대로 sub로 쓴다 — AuthorityControllerTest와 같은 방식이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AcademicProgramTypeControllerTest.StubJwtDecoderConfig.class)
@Transactional
class AcademicProgramTypeControllerTest {

    private static final String TYPES = "/v1/academic-program-types";

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
    @Autowired private AcademicProgramTypeRepository academicProgramTypeRepository;

    private UUID managerToken;
    private UUID outsiderToken;

    @BeforeEach
    void setUp() {
        managerToken = UUID.randomUUID();
        MemberEntity manager = saveMember(managerToken, "20260301", "학술국장");
        grant(manager, AuthorityCode.ACADEMIC_PROGRAM_MANAGE);

        // ACADEMIC_PROGRAM_MANAGE가 없는 회원. 조회는 인증만으로 통과해야 하고 쓰기만 막혀야
        // 한다는 것을 드러내려면 권한이 아예 없는 것이 아니라 '다른 권한만' 가진 쪽이어야 한다
        outsiderToken = UUID.randomUUID();
        MemberEntity outsider = saveMember(outsiderToken, "20260302", "국원");
        grant(outsider, AuthorityCode.WORK_MANAGE);
    }

    // ------------------------------------------------------------------ 목록 조회

    // 시드된 STUDY/PROJECT가 indctSeqno 순으로 내려온다. 조회는 인증만으로 통과한다(outsider)
    @Test
    void returnsSeededTypesOrderedByDisplaySeqno() throws Exception {
        mockMvc.perform(authorized(get(TYPES), outsiderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].typeCd").value("STUDY"))
                .andExpect(jsonPath("$.data[0].typeNm").value("스터디"))
                .andExpect(jsonPath("$.data[0].useYn").value(true))
                .andExpect(jsonPath("$.data[1].typeCd").value("PROJECT"));
    }

    // 비활성 유형도 관리 목록에는 남는다 — 되돌릴 길이 없어지면 안 된다
    @Test
    void listIncludesInactiveTypes() throws Exception {
        mockMvc.perform(
                        authorized(patch(TYPES + "/STUDY/activation"), managerToken)
                                .content(
                                        """
                                        {"useYn": false}
                                        """))
                .andExpect(status().isOk());

        mockMvc.perform(authorized(get(TYPES), outsiderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].typeCd").value("STUDY"))
                .andExpect(jsonPath("$.data[0].useYn").value(false));
    }

    // ------------------------------------------------------------------ 등록

    @Test
    void createsNewType() throws Exception {
        mockMvc.perform(
                        authorized(post(TYPES), managerToken)
                                .content(
                                        """
                                        {"typeCd": "SEMINAR", "typeNm": "세미나", "indctSeqno": 3}
                                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.typeCd").value("SEMINAR"))
                .andExpect(jsonPath("$.data.typeNm").value("세미나"))
                .andExpect(jsonPath("$.data.useYn").value(true));

        flushAndClear();
        assertThat(academicProgramTypeRepository.findById("SEMINAR")).isPresent();
    }

    // 이미 있는 typeCd로 등록하면 409다 — 화면이 중복 등록을 조용히 덮어쓰지 않는다
    @Test
    void rejectsDuplicateTypeCode() throws Exception {
        mockMvc.perform(
                        authorized(post(TYPES), managerToken)
                                .content(
                                        """
                                        {"typeCd": "STUDY", "typeNm": "스터디 사칭", "indctSeqno": 9}
                                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACADEMIC_PROGRAM_TYPE_CODE_DUPLICATED"));
    }

    // ACADEMIC_PROGRAM_MANAGE가 없으면 쓰기가 403이다 — 조회(outsider도 통과)와 갈린다
    @Test
    void createWithoutManageAuthorityIsForbidden() throws Exception {
        mockMvc.perform(
                        authorized(post(TYPES), outsiderToken)
                                .content(
                                        """
                                        {"typeCd": "SEMINAR", "typeNm": "세미나", "indctSeqno": 3}
                                        """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // ------------------------------------------------------------------ 수정

    // 본문의 typeCd는 무시된다 — 경로의 값이 유일한 식별자다
    @Test
    void updateIgnoresBodyTypeCd() throws Exception {
        mockMvc.perform(
                        authorized(patch(TYPES + "/PROJECT"), managerToken)
                                .content(
                                        """
                                        {"typeCd": "STUDY", "typeNm": "프로젝트(수정)", "indctSeqno": 5}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.typeCd").value("PROJECT"))
                .andExpect(jsonPath("$.data.typeNm").value("프로젝트(수정)"))
                .andExpect(jsonPath("$.data.indctSeqno").value(5));

        flushAndClear();
        assertThat(academicProgramTypeRepository.findById("STUDY")).isPresent();
    }

    @Test
    void updateUnknownTypeReturnsNotFound() throws Exception {
        mockMvc.perform(
                        authorized(patch(TYPES + "/NO_SUCH_TYPE"), managerToken)
                                .content(
                                        """
                                        {"typeCd": "NO_SUCH_TYPE", "typeNm": "없음", "indctSeqno": 1}
                                        """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACADEMIC_PROGRAM_TYPE_NOT_FOUND"));
    }

    // ------------------------------------------------------------------ 사용 여부 전환

    @Test
    void togglesActivation() throws Exception {
        mockMvc.perform(
                        authorized(patch(TYPES + "/PROJECT/activation"), managerToken)
                                .content(
                                        """
                                        {"useYn": false}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.useYn").value(false));

        flushAndClear();
        assertThat(academicProgramTypeRepository.findById("PROJECT").orElseThrow().isActive())
                .isFalse();
    }

    // ------------------------------------------------------------------ 헬퍼

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

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
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
