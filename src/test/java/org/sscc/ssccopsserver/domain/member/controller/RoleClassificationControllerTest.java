package org.sscc.ssccopsserver.domain.member.controller;

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
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleClassificationEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleEntity;
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
 * 역할 분류 관리 API (#80 · ssccops#23).
 *
 * 확인의 중심은 **"분류가 사라져도 역할이 갈 곳을 잃지 않는다"**이다 — 소속 역할이 있는 분류는
 * 지워지지 않고, 시드가 '최고관리자'를 두는 SYSTEM은 지워지지도 이름이 바뀌지도 않는다.
 * 반대로 시드 5종의 이름은 바뀌어야 하고(조직이 정할 몫이다) 코드는 그대로여야 한다.
 *
 * 인가가 핸들러마다 갈리는 것도 여기서 못 박는다. 조회는 ROLE_MANAGE 없이도 200이어야 하고
 * (역할 목록의 필터 칩이 쓰는 값이다) 변경 셋은 403이어야 한다.
 *
 * 요청 주체를 요청마다 바꿔야 해서 JwtDecoder 스텁이 토큰 문자열을 그대로 sub로 쓴다 —
 * AuthorityControllerTest와 같은 방식이다.
 *
 * 서비스가 예외를 던지면 참여 중인 테스트 트랜잭션이 rollback-only로 표시되므로, 실패를 보는
 * 테스트는 실패하는 요청 하나로 끝낸다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RoleClassificationControllerTest.StubJwtDecoderConfig.class)
@Transactional
class RoleClassificationControllerTest {

    private static final String CLASSIFICATIONS = "/v1/role-classifications";

    @Autowired private MockMvc mockMvc;
    @Autowired private EntityManager entityManager;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository roleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private AuthorityRepository authorityRepository;
    @Autowired private RoleAuthorityRelationRepository roleAuthorityRelationRepository;

    private UUID adminToken;
    private UUID outsiderToken;

    @BeforeEach
    void setUp() {
        adminToken = UUID.randomUUID();
        MemberEntity admin = saveMember(adminToken, "20260201", "최고운영자");
        grant(admin, AuthorityCode.ROLE_MANAGE);

        // ROLE_MANAGE가 없는 회원. 권한이 아예 없는 것이 아니라 '다른 권한만' 가진 쪽이어야
        // 403이 인증·가입이 아니라 권한 때문이라는 것이 드러난다
        outsiderToken = UUID.randomUUID();
        MemberEntity outsider = saveMember(outsiderToken, "20260202", "업무담당");
        grant(outsider, AuthorityCode.WORK_MANAGE);
    }

    // ------------------------------------------------------------------ 목록 조회

    /*
     * 시드 6종이 indct_seqno 순으로 내려온다. 순서를 못 박는 것은 화면이 이 배열을 그대로
     * 그리기 때문이다 — 서버가 정렬하지 않으면 필터 칩의 순서가 요청마다 달라진다.
     *
     * SYSTEM의 roleCount가 1인 것은 시드가 '최고관리자'를 여기 두기 때문이다(#71). 이 숫자가
     * 0이 되면 그 역할이 사라졌다는 뜻이고 부트스트랩이 이미 깨져 있다.
     */
    @Test
    void listsSeededClassificationsInDisplayOrder() throws Exception {
        mockMvc.perform(authorized(get(CLASSIFICATIONS), adminToken))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data[*].roleClsfCd")
                                .value(
                                        contains(
                                                "POSITION",
                                                "DEPT",
                                                "PROJECT",
                                                "STUDY",
                                                "EVENT",
                                                "SYSTEM")))
                .andExpect(jsonPath("$.data[0].roleClsfNm").value("직책"))
                .andExpect(jsonPath("$.data[0].indctSeqno").value(1))
                .andExpect(jsonPath("$.data[5].roleClsfNm").value("시스템"))
                .andExpect(jsonPath("$.data[5].roleCount").value(1))
                // use_yn은 데이터사전에 없는 컬럼이다 — 없는 개념을 응답에 만들지 않는다
                .andExpect(jsonPath("$.data[0].useYn").doesNotExist());
    }

    /*
     * 조회는 ROLE_MANAGE 없이 200이다. 역할 목록의 필터 칩이 이 값을 쓰므로 막으면 역할을 볼
     * 수 있는 사람이 분류로 거르지 못한다 — 권한 관리(#65)가 조회까지 잠그는 것과 갈린다.
     */
    @Test
    void listIsOpenToAnySignedUpMember() throws Exception {
        mockMvc.perform(authorized(get(CLASSIFICATIONS), outsiderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].roleClsfCd").value("POSITION"));
    }

    // ------------------------------------------------------------------ 생성

    /*
     * 만든 분류는 목록에 나타나고 역할이 그것을 고를 수 있다. 뒤쪽이 요점이다 — 웹 목 구현이
     * 프런트에서 채번하던 CLSF_1은 서버에 없는 코드라 role.role_clsf_cd로 저장될 수 없었다.
     */
    @Test
    void createdClassificationAppearsInListAndCanHoldRoles() throws Exception {
        mockMvc.perform(
                        authorized(post(CLASSIFICATIONS), adminToken)
                                .content(createBody("TF", "TF", 7)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.roleClsfCd").value("TF"))
                .andExpect(jsonPath("$.data.roleClsfNm").value("TF"))
                .andExpect(jsonPath("$.data.indctSeqno").value(7))
                // 갓 만든 분류를 쓰는 역할은 있을 수 없다
                .andExpect(jsonPath("$.data.roleCount").value(0));

        flushAndClear();
        MemberRoleClassificationEntity created =
                roleClassificationRepository.findById("TF").orElseThrow();
        memberRoleRepository.saveAndFlush(MemberRoleEntity.create(1, "TF장", created));
        flushAndClear();

        mockMvc.perform(authorized(get(CLASSIFICATIONS), adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[6].roleClsfCd").value("TF"))
                .andExpect(jsonPath("$.data[6].roleCount").value(1));
    }

    /** indctSeqno를 생략해도 만들어진다 — 순서를 정하지 않았다고 생성이 막힐 이유가 없다 */
    @Test
    void createsClassificationWithDefaultDisplayOrder() throws Exception {
        mockMvc.perform(
                        authorized(post(CLASSIFICATIONS), adminToken)
                                .content("{\"roleClsfCd\": \"TF\", \"roleClsfNm\": \"TF\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.indctSeqno").value(99));
    }

    @Test
    void rejectsDuplicatedClassificationCode() throws Exception {
        mockMvc.perform(
                        authorized(post(CLASSIFICATIONS), adminToken)
                                .content(createBody("POSITION", "직책 사칭", 9)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROLE_CLASSIFICATION_CODE_DUPLICATED"));
    }

    /*
     * 소문자는 형식 위반이다. 코드값은 표준코드 시트에 등재되는 값이라 표기가 갈리면 시트와
     * 화면에 두 벌의 어휘가 생긴다 — 웹 목 구현의 CLSF_1도 여기서 막힌다면 좋겠지만 그쪽은
     * 형식은 맞고 의미가 없는 것이라, 막는 것은 표기뿐이다.
     */
    @Test
    void rejectsLowercaseClassificationCode() throws Exception {
        mockMvc.perform(
                        authorized(post(CLASSIFICATIONS), adminToken)
                                .content(createBody("tf", "TF", 7)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /** 하이픈은 UPPER_SNAKE_CASE가 아니다 */
    @Test
    void rejectsHyphenatedClassificationCode() throws Exception {
        mockMvc.perform(
                        authorized(post(CLASSIFICATIONS), adminToken)
                                .content(createBody("TASK-FORCE", "TF", 7)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /** 한 글자 코드는 시트에서 구별되지 않는다 — 최소 2자다 */
    @Test
    void rejectsSingleCharacterClassificationCode() throws Exception {
        mockMvc.perform(
                        authorized(post(CLASSIFICATIONS), adminToken)
                                .content(createBody("T", "TF", 7)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /** 컬럼이 VARCHAR(20)이라 21자는 들어가지 않는다 — DB가 자르기 전에 거절한다 */
    @Test
    void rejectsTooLongClassificationCode() throws Exception {
        mockMvc.perform(
                        authorized(post(CLASSIFICATIONS), adminToken)
                                .content(createBody("A".repeat(21), "TF", 7)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ------------------------------------------------------------------ 수정

    /*
     * 시드 분류의 이름은 바뀌고 코드는 그대로다. 코드값이 유지되면 role의 참조가 깨지지 않고,
     * 표시명은 조직이 정할 일이다.
     */
    @Test
    void seededClassificationCanBeRenamedWithoutChangingItsCode() throws Exception {
        mockMvc.perform(
                        authorized(patch(CLASSIFICATIONS + "/DEPT"), adminToken)
                                .content("{\"roleClsfNm\": \"국\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roleClsfCd").value("DEPT"))
                .andExpect(jsonPath("$.data.roleClsfNm").value("국"))
                // indctSeqno를 생략하면 현재 값을 유지한다
                .andExpect(jsonPath("$.data.indctSeqno").value(2));

        flushAndClear();
        assertThat(roleClassificationRepository.findById("DEPT").orElseThrow().getName())
                .isEqualTo("국");
    }

    /** 표시 순번만 옮기는 것도 이름과 같은 경로다 */
    @Test
    void classificationDisplayOrderCanBeChanged() throws Exception {
        mockMvc.perform(
                        authorized(patch(CLASSIFICATIONS + "/EVENT"), adminToken)
                                .content("{\"roleClsfNm\": \"행사\", \"indctSeqno\": 1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.indctSeqno").value(1));

        flushAndClear();
        mockMvc.perform(authorized(get(CLASSIFICATIONS), adminToken))
                .andExpect(status().isOk())
                // 순번이 같으면 코드로 끊는다 — EVENT < POSITION
                .andExpect(jsonPath("$.data[0].roleClsfCd").value("EVENT"));
    }

    /*
     * SYSTEM의 이름은 바뀌지 않는다. '시스템'이라는 이름은 조직이 만든 자리가 아니라 시스템이
     * 쓰는 역할을 담는 칸이라는 표시 그 자체라, 바뀌면 최고관리자가 조직 직책인 것처럼 선다.
     */
    @Test
    void systemClassificationRenameIsRejected() throws Exception {
        mockMvc.perform(
                        authorized(patch(CLASSIFICATIONS + "/SYSTEM"), adminToken)
                                .content("{\"roleClsfNm\": \"홍보국\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SYSTEM_ROLE_CLASSIFICATION_IMMUTABLE"));
    }

    /*
     * 같은 이름을 다시 보내는 것은 이름 변경이 아니다. 화면이 분류 한 벌을 통째로 들고
     * 저장하므로 SYSTEM의 순번만 옮기려는 정상 요청에도 현재 이름이 실려 온다 — 그것까지
     * 막으면 SYSTEM은 순서를 영영 바꿀 수 없다.
     */
    @Test
    void systemClassificationDisplayOrderCanBeChangedWithSameName() throws Exception {
        mockMvc.perform(
                        authorized(patch(CLASSIFICATIONS + "/SYSTEM"), adminToken)
                                .content("{\"roleClsfNm\": \"시스템\", \"indctSeqno\": 9}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roleClsfNm").value("시스템"))
                .andExpect(jsonPath("$.data.indctSeqno").value(9))
                // 소속 역할 수는 저장 직후에도 그대로 보여야 한다
                .andExpect(jsonPath("$.data.roleCount").value(1));
    }

    @Test
    void unknownClassificationReturnsNotFound() throws Exception {
        mockMvc.perform(
                        authorized(patch(CLASSIFICATIONS + "/NO_SUCH_CLSF"), adminToken)
                                .content("{\"roleClsfNm\": \"없는 분류\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROLE_CLASSIFICATION_NOT_FOUND"));
    }

    // ------------------------------------------------------------------ 삭제

    @Test
    void deletesClassificationWithoutRoles() throws Exception {
        saveClassification("TF", "TF", 7);

        mockMvc.perform(authorized(delete(CLASSIFICATIONS + "/TF"), adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());

        flushAndClear();
        assertThat(roleClassificationRepository.findById("TF")).isEmpty();
    }

    /*
     * 소속 역할이 있으면 지워지지 않는다. role.role_clsf_cd가 NOT NULL FK라 지우면 역할이 갈
     * 곳을 잃는다 — 함께 지우거나 다른 분류로 조용히 옮기면 삭제 한 번으로 조직도가 바뀐다.
     */
    @Test
    void classificationWithRolesCannotBeDeleted() throws Exception {
        MemberRoleClassificationEntity classification = saveClassification("TF", "TF", 7);
        memberRoleRepository.saveAndFlush(MemberRoleEntity.create(1, "TF장", classification));

        mockMvc.perform(authorized(delete(CLASSIFICATIONS + "/TF"), adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROLE_CLASSIFICATION_IN_USE"));
    }

    /*
     * 역할을 다른 분류로 옮기면 지워진다. 이것이 코드를 바꾸는 대신 안내하는 경로다 —
     * 새로 만들고 → 역할을 옮기고 → 기존 것을 지운다.
     *
     * 옮기는 조작 자체는 역할 수정 API(#79)의 몫이라 여기서는 엔티티로 바꾼다. 앞선 요청이
     * 실패하면 테스트 트랜잭션이 rollback-only가 되므로 409 확인과 한 테스트에 두지 않는다.
     */
    @Test
    void classificationBecomesDeletableAfterItsRolesMoveAway() throws Exception {
        MemberRoleClassificationEntity classification = saveClassification("TF", "TF", 7);
        MemberRoleEntity role =
                memberRoleRepository.saveAndFlush(
                        MemberRoleEntity.create(1, "TF장", classification));

        MemberRoleClassificationEntity position =
                roleClassificationRepository.findById("POSITION").orElseThrow();
        role.update(role.getDisplayOrder(), role.getName(), position, role.getPositionCode());
        entityManager.flush();

        mockMvc.perform(authorized(delete(CLASSIFICATIONS + "/TF"), adminToken))
                .andExpect(status().isOk());

        flushAndClear();
        assertThat(roleClassificationRepository.findById("TF")).isEmpty();
        assertThat(
                        memberRoleRepository
                                .findById(role.getId())
                                .orElseThrow()
                                .getRoleClassification()
                                .getCode())
                .isEqualTo("POSITION");
    }

    /*
     * SYSTEM은 지워지지 않는다. data.sql이 '최고관리자'를 여기 두므로 사라지면 최초 가입자
     * 부트스트랩(#71)이 통째로 깨져 새 환경에서 아무도 ROLE_MANAGE를 얻지 못한다.
     */
    @Test
    void systemClassificationDeleteIsRejected() throws Exception {
        mockMvc.perform(authorized(delete(CLASSIFICATIONS + "/SYSTEM"), adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SYSTEM_ROLE_CLASSIFICATION_IMMUTABLE"));
    }

    // ------------------------------------------------------------------ 인가

    @Test
    void createWithoutRoleManageIsForbidden() throws Exception {
        mockMvc.perform(
                        authorized(post(CLASSIFICATIONS), outsiderToken)
                                .content(createBody("TF", "TF", 7)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void updateWithoutRoleManageIsForbidden() throws Exception {
        mockMvc.perform(
                        authorized(patch(CLASSIFICATIONS + "/DEPT"), outsiderToken)
                                .content("{\"roleClsfNm\": \"국\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void deleteWithoutRoleManageIsForbidden() throws Exception {
        mockMvc.perform(authorized(delete(CLASSIFICATIONS + "/EVENT"), outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
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
                roleClassificationRepository,
                memberRoleAssignmentRepository,
                authorityRepository,
                roleAuthorityRelationRepository,
                member,
                authority);
    }

    private MemberRoleClassificationEntity saveClassification(
            String code, String name, int displayOrder) {

        MemberRoleClassificationEntity classification =
                MemberRoleClassificationEntity.create(code, name, displayOrder);
        entityManager.persist(classification);
        entityManager.flush();
        return classification;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private static String createBody(String code, String name, int displayOrder) {
        return "{\"roleClsfCd\": \"%s\", \"roleClsfNm\": \"%s\", \"indctSeqno\": %d}"
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
