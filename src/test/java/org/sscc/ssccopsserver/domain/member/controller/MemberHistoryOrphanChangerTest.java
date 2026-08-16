package org.sscc.ssccopsserver.domain.member.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.code.MemberGradeCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberGradeHistoryEntity;
import org.sscc.ssccopsserver.domain.member.repository.AuthorityRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeHistoryRepository;
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
 * 변경자 회원이 사라진 이력도 500 없이 내려간다 (#82).
 *
 * 이 서비스는 회원을 지우지 않지만(탈퇴·제명은 상태 변경이고 mbr 행은 남는다) 이관이나 수동
 * 정리로 참조가 끊긴 행은 생길 수 있다. 그때 이력 조회 전체가 500이면 남은 줄까지 함께 못
 * 보게 되므로, 변경자 자리만 비고 나머지는 그대로 실려야 한다.
 *
 * **@Transactional을 걸 수 없어 클래스를 나눴다.** 참조가 끊긴 행을 만들려면 H2의 참조 무결성
 * 검사를 잠깐 내려야 하는데 SET 문이 암묵 커밋을 일으켜, 테스트 트랜잭션 안에서 쓰면 그때까지
 * 만든 픽스처가 통째로 커밋되어 뒤따르는 테스트가 학번 UNIQUE 충돌로 무너진다
 * (MemberChangeRollbackTest가 클래스를 나눈 것과 같은 사정). 대신 남긴 행을 @Sql로 지운다 —
 * 시드(data.sql)가 넣은 역할·매핑은 건드리지 않고 이 테스트가 만든 것만 지운다.
 *
 * 컨텍스트 설정을 MemberHistoryControllerTest와 똑같이 두는 것은 캐시된 컨텍스트를 그대로
 * 쓰기 위해서다 — 다르게 두면 애플리케이션이 한 번 더 기동한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MemberHistoryControllerTest.HistoryTestConfig.class)
@Sql(
        executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD,
        statements = {
            "DELETE FROM mbr_grd_hstry",
            "DELETE FROM mbr_stts_hstry",
            "DELETE FROM mbr_role_rel",
            "DELETE FROM role_authrt_rel WHERE role_id IN"
                    + " (SELECT role_id FROM role WHERE role_nm LIKE '역할:%')",
            "DELETE FROM role WHERE role_nm LIKE '역할:%'",
            "DELETE FROM mbr"
        })
class MemberHistoryOrphanChangerTest {

    private static final UUID MANAGER = UUID.randomUUID();

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberGradeHistoryRepository memberGradeHistoryRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private AuthorityRepository authorityRepository;
    @Autowired private RoleAuthorityRelationRepository roleAuthorityRelationRepository;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void historyWhoseChangerRowIsGoneStillResponds() throws Exception {
        MemberEntity manager = saveMember(MANAGER, "20300001", "김도현");
        AuthorityFixture.grant(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                authorityRepository,
                roleAuthorityRelationRepository,
                manager,
                AuthorityCode.MEMBER_MANAGE);

        MemberEntity target = saveMember(UUID.randomUUID(), "20300002", "박준호");
        MemberEntity ghost = saveMember(UUID.randomUUID(), "20300003", "떠난 운영진");

        // 변경자가 남아 있는 줄과 사라질 줄을 함께 둔다 — 한 줄이 깨져도 나머지는 실려야 한다
        saveGradeHistory(target, "회원가입", manager);
        saveGradeHistory(target, "준회원 승급", ghost);
        deleteMemberRow(ghost.getId());

        mockMvc.perform(
                        get("/v1/members/" + target.getId() + "/histories")
                                .header("Authorization", "Bearer " + MANAGER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].changeReason").value("준회원 승급"))
                .andExpect(jsonPath("$.data[0].changedByMemberId").doesNotExist())
                .andExpect(jsonPath("$.data[0].changedByName").doesNotExist())
                .andExpect(jsonPath("$.data[1].changedByName").value("김도현"));
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

    private void saveGradeHistory(MemberEntity target, String reason, MemberEntity changedBy) {
        memberGradeHistoryRepository.save(
                MemberGradeHistoryEntity.create(
                        target,
                        null,
                        memberGradeRepository.findById(MemberGradeCode.TEMP.code()).orElseThrow(),
                        LocalDate.of(2026, 8, 19),
                        reason,
                        changedBy));
    }

    /*
     * 회원 행을 직접 지운다. chnrg_mbr_id가 FK로 잡혀 있어 평범한 삭제로는 참조가 끊긴 상태를
     * 만들 수 없으므로 참조 무결성 검사를 잠깐 내린다. 실패해도 다시 켜지도록 finally에 둔다 —
     * 켜지지 않은 채로 남으면 이후 모든 테스트가 FK 위반을 못 잡는다.
     */
    private void deleteMemberRow(Long memberId) {
        try {
            jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
            jdbcTemplate.update("delete from mbr where mbr_id = ?", memberId);
        } finally {
            jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
    }
}
