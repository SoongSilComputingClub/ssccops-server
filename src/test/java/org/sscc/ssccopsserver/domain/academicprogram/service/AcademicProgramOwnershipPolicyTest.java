package org.sscc.ssccopsserver.domain.academicprogram.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.academicprogram.code.error.AcademicProgramErrorCode;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramTypeRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.CurriculumItemRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventClassificationRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;
import org.sscc.ssccopsserver.support.AcademicProgramFixture;
import org.sscc.ssccopsserver.support.MemberFixture;

/*
 * "이 활동의 스터디장/팀장 본인인가"의 유일한 구현 (#133). leadrMbrId는 생성 시점부터 항상
 * prpsrMbrId와 같은 값이므로(2026-08-24 재설계), 픽스처가 만드는 리더가 곧 이 정책이 보는
 * leadrMbrId다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AcademicProgramOwnershipPolicyTest {

    @Autowired private AcademicProgramOwnershipPolicy ownershipPolicy;

    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private EventClassificationRepository eventClassificationRepository;
    @Autowired private AcademicProgramRepository academicProgramRepository;
    @Autowired private AcademicProgramTypeRepository academicProgramTypeRepository;
    @Autowired private CurriculumItemRepository curriculumItemRepository;

    @Test
    void leaderPassesOwnershipCheck() {
        MemberEntity leader = saveMember("20260401", "리더");
        AcademicProgramEntity academicProgram = createAcademicProgram("STUDY", "본인 스터디", leader);

        assertThatCode(() -> ownershipPolicy.requireLeader(academicProgram, leader))
                .doesNotThrowAnyException();
        assertThat(ownershipPolicy.isLeader(academicProgram, leader)).isTrue();
    }

    @Test
    void nonLeaderIsRejectedWith403() {
        MemberEntity leader = saveMember("20260402", "리더2");
        MemberEntity other = saveMember("20260403", "타인");
        AcademicProgramEntity academicProgram = createAcademicProgram("STUDY", "남의 스터디", leader);

        assertThatThrownBy(() -> ownershipPolicy.requireLeader(academicProgram, other))
                .isInstanceOf(GeneralException.class)
                .satisfies(
                        ex ->
                                assertThat(((GeneralException) ex).getErrorCode())
                                        .isEqualTo(AcademicProgramErrorCode.FORBIDDEN));
        assertThat(ownershipPolicy.isLeader(academicProgram, other)).isFalse();
    }

    @Test
    void nullRequesterIsNotLeader() {
        MemberEntity leader = saveMember("20260404", "리더3");
        AcademicProgramEntity academicProgram = createAcademicProgram("STUDY", "익명 접근 스터디", leader);

        assertThat(ownershipPolicy.isLeader(academicProgram, null)).isFalse();
    }

    // ------------------------------------------------------------------ 헬퍼

    private AcademicProgramEntity createAcademicProgram(
            String typeCd, String title, MemberEntity leaderAndProposer) {
        return AcademicProgramFixture.save(
                eventRepository,
                eventClassificationRepository,
                academicProgramRepository,
                academicProgramTypeRepository,
                curriculumItemRepository,
                typeCd,
                title,
                leaderAndProposer,
                List.of("1주차"));
    }

    private MemberEntity saveMember(String studentNumber, String name) {
        return MemberFixture.save(
                memberRepository,
                memberGradeRepository,
                memberStatusRepository,
                UUID.randomUUID(),
                studentNumber,
                name,
                studentNumber + "@sscc.org");
    }
}
