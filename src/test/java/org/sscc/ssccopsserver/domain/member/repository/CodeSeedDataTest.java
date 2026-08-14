package org.sscc.ssccopsserver.domain.member.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.Comparator;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.sscc.ssccopsserver.domain.member.entity.MemberGradeEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleClassificationEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberStatusEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkTypeEntity;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkTypeRepository;

/*
 * data.sql이 넣는 기준 코드·기준 데이터 검증.
 *
 * 기대값을 enum이 아니라 문자열 리터럴로 적은 것은 의도된 것이다. 이 코드값들은 웹
 * shared/config/codes.ts와 맞춰 놓은 계약이라, enum 상수 이름이 바뀌었을 때 테스트가 따라
 * 바뀌어 조용히 통과해 버리면 안 된다. 여기서만큼은 서버 코드가 아니라 계약을 적는다.
 */
@DataJpaTest
@ActiveProfiles("test")
class CodeSeedDataTest {

    @Autowired private DataSource dataSource;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private SubWorkTypeRepository subWorkTypeRepository;

    @Test
    void seedsEveryGradeCodeTheWebRenders() {
        assertThat(memberGradeRepository.findAll())
                .extracting(MemberGradeEntity::getCode, MemberGradeEntity::getName)
                .containsExactlyInAnyOrder(
                        tuple("TEMP", "임시회원"),
                        tuple("ASSOC", "준회원"),
                        tuple("ACTIVE", "활동회원"),
                        tuple("FULL", "정회원"));
    }

    @Test
    void seedsEveryStatusCodeTheWebRenders() {
        assertThat(memberStatusRepository.findAll())
                .extracting(MemberStatusEntity::getCode, MemberStatusEntity::getName)
                .containsExactlyInAnyOrder(
                        tuple("ENROLLED", "재학"),
                        tuple("LEAVE", "일반휴학"),
                        tuple("MIL_LEAVE", "군휴학"),
                        tuple("GRADUATED", "졸업"),
                        tuple("WITHDRAWN", "탈퇴"),
                        tuple("EXPELLED", "제명"));
    }

    // 졸업 회원 가입(#21)이 막혀 있던 직접적인 원인이라 따로 못 박아 둔다
    @Test
    void seedsGraduatedStatusSoThatGraduateSignUpIsPossible() {
        assertThat(memberStatusRepository.findById("GRADUATED")).isPresent();
    }

    @Test
    void seedsRoleClassifications() {
        assertThat(memberRoleClassificationRepository.findAll())
                .extracting(MemberRoleClassificationEntity::getCode)
                .contains("POSITION", "DEPT", "PROJECT", "STUDY", "EVENT");
    }

    /*
     * 역할 표시순번이 서열 오름차순인지 확인한다. role에 서열 컬럼이 없어 "국장 이상"(#9) 판정이
     * 이 순번에 얹히므로, 순번이 흐트러지면 인가 판정이 조용히 틀어진다.
     */
    @Test
    void ordersRolesBySeniority() {
        assertThat(
                        memberRoleRepository.findAll().stream()
                                .sorted(Comparator.comparing(MemberRoleEntity::getDisplayOrder))
                                .map(MemberRoleEntity::getName)
                                .toList())
                .startsWith("회장", "부회장", "총무", "국장");
    }

    // 승인이 필요한 유형에는 승인자가 있고, 필요 없는 유형에는 없어야 한다
    @Test
    void assignsAuthorizerRoleOnlyToTypesThatNeedApproval() {
        assertThat(subWorkTypeRepository.findAll())
                .allSatisfy(
                        type ->
                                assertThat(type.getAuthorizerRoleCode() != null)
                                        .isEqualTo(type.isApprovalNeeded()));

        assertThat(subWorkTypeRepository.findById(1L))
                .get()
                .extracting(SubWorkTypeEntity::getAuthorizerRoleCode)
                .isEqualTo("TREASURER");
        assertThat(subWorkTypeRepository.findById(2L))
                .get()
                .extracting(SubWorkTypeEntity::getAuthorizerRoleCode)
                .isEqualTo("PRESIDENT");
    }

    /*
     * spring.sql.init.mode=always라 data.sql은 매 기동마다 실행된다. 재기동을 흉내 내려고
     * 부트가 쓰는 것과 같은 populator로 스크립트를 한 번 더 돌린 뒤, 건수가 그대로인지 본다.
     * WHERE NOT EXISTS가 빠진 INSERT가 하나라도 섞이면 여기서 잡힌다.
     */
    @Test
    void reRunningTheSeedScriptChangesNothing() {
        long grades = memberGradeRepository.count();
        long statuses = memberStatusRepository.count();
        long classifications = memberRoleClassificationRepository.count();
        long roles = memberRoleRepository.count();
        long subWorkTypes = subWorkTypeRepository.count();

        new ResourceDatabasePopulator(new ClassPathResource("data.sql")).execute(dataSource);

        assertThat(memberGradeRepository.count()).isEqualTo(grades);
        assertThat(memberStatusRepository.count()).isEqualTo(statuses);
        assertThat(memberRoleClassificationRepository.count()).isEqualTo(classifications);
        assertThat(memberRoleRepository.count()).isEqualTo(roles);
        assertThat(subWorkTypeRepository.count()).isEqualTo(subWorkTypes);
    }
}
