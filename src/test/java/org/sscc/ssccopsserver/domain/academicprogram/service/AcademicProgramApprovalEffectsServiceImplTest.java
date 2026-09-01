package org.sscc.ssccopsserver.domain.academicprogram.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramTypeRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.CurriculumItemRepository;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.event.repository.EventClassificationRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseHistoryRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleAssignmentEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleClassificationRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.support.AcademicProgramFixture;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.MemberRoleFixture;

/*
 * 승인 후속 처리(#133) — 스터디장/팀장 역할 부여 + 빈 모집 폼 생성 + Event 연결이 한
 * 트랜잭션으로 원자적인지 검증한다. #150(승인 이관)이 아직 없어 AcademicProgramFixture로 직접
 * AcademicProgram을 만들어 이 서비스만 단독으로 부른다(#131이 등록 API 없이 조회를 픽스처로
 * 먼저 검증한 것과 같은 방식).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AcademicProgramApprovalEffectsServiceImplTest {

    @Autowired private AcademicProgramApprovalEffectsService effectsService;
    @Autowired private EntityManager entityManager;

    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;

    @Autowired private EventRepository eventRepository;
    @Autowired private EventClassificationRepository eventClassificationRepository;
    @Autowired private AcademicProgramRepository academicProgramRepository;
    @Autowired private AcademicProgramTypeRepository academicProgramTypeRepository;
    @Autowired private CurriculumItemRepository curriculumItemRepository;
    @Autowired private FormResponseHistoryRepository formResponseHistoryRepository;
    @Autowired private FormRepository formRepository;

    @Test
    void assignsStudyLeaderRoleAndLinksEmptyDraftForm() {
        MemberEntity leader = saveMember("20260401", "스터디리더");
        AcademicProgramEntity academicProgram = createAcademicProgram("STUDY", "알고리즘 스터디", leader);

        effectsService.applyPostApprovalEffects(academicProgram);
        flushAndClear();

        List<MemberRoleAssignmentEntity> assignments =
                memberRoleAssignmentRepository.findCurrentByMemberId(leader.getId());
        assertThat(assignments)
                .extracting(assignment -> assignment.getRole().getName())
                .contains(MemberRoleFixture.STUDY_LEADER);

        EventEntity event = academicProgram.getEvent();
        assertThat(event.getForm()).isNotNull();
        FormEntity form = formRepository.findById(event.getForm().getId()).orElseThrow();
        assertThat(form.getStatus()).isEqualTo(FormStatus.DRAFT);
        assertThat(form.getQuestionComposition().qitems()).isEmpty();
        // 빈 폼이라도 pages는 비우지 않는다 (#186) — NULL이면 상세 응답에서 키가 빠져 편집기가 죽는다
        assertThat(form.getQuestionComposition().pages()).isNotEmpty();
        assertThat(form.getTitle()).isEqualTo("알고리즘 스터디 모집");
    }

    @Test
    void assignsProjectLeaderRoleForProjectType() {
        MemberEntity leader = saveMember("20260402", "프로젝트리더");
        AcademicProgramEntity academicProgram = createAcademicProgram("PROJECT", "졸업 프로젝트", leader);

        effectsService.applyPostApprovalEffects(academicProgram);
        flushAndClear();

        List<MemberRoleAssignmentEntity> assignments =
                memberRoleAssignmentRepository.findCurrentByMemberId(leader.getId());
        assertThat(assignments)
                .extracting(assignment -> assignment.getRole().getName())
                .contains("프로젝트장");
    }

    /*
     * 이미 리더 역할을 갖고 있어도 승인 후속 처리는 끝까지 간다.
     *
     * 한 사람이 스터디를 둘 이상 이끄는 것은 정상이므로, 역할 부여는 건너뛰되 모집 폼은 만들어져야
     * 한다 — 여기서 실패하면 부작용 하나 때문에 기획안 승인 자체가 롤백된다. 배정이 **늘지 않는
     * 것**까지 함께 못 박는다: 통과만 확인하면 중복 배정이 쌓이는 구현도 이 테스트를 지난다.
     */
    @Test
    void skipsLeaderRoleAssignmentWhenAlreadyAssigned() {
        MemberEntity leader = saveMember("20260403", "이미리더");
        // 오늘부터 무기한으로 이미 스터디장 역할을 갖고 있다 — 새 배정과 기간이 겹친다
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                leader,
                MemberRoleFixture.STUDY_LEADER);

        AcademicProgramEntity academicProgram = createAcademicProgram("STUDY", "중복 리더 스터디", leader);
        long formCountBefore = formRepository.count();
        int assignmentCountBefore =
                memberRoleAssignmentRepository.findCurrentByMemberId(leader.getId()).size();

        effectsService.applyPostApprovalEffects(academicProgram);
        flushAndClear();

        // 배정은 늘지 않는다 — 이미 가진 역할을 한 벌 더 만들지 않는다
        assertThat(memberRoleAssignmentRepository.findCurrentByMemberId(leader.getId()))
                .hasSize(assignmentCountBefore);
        // 그래도 모집 폼은 만들어진다 — 승인 본체가 롤백되지 않는다
        assertThat(formRepository.count()).isEqualTo(formCountBefore + 1);
        assertThat(academicProgram.getEvent().getForm()).isNotNull();
    }

    // ------------------------------------------------------------------ 헬퍼

    private AcademicProgramEntity createAcademicProgram(
            String typeCd, String title, MemberEntity leaderAndProposer) {
        AcademicProgramEntity academicProgram =
                AcademicProgramFixture.save(
                        eventRepository,
                        eventClassificationRepository,
                        academicProgramRepository,
                        academicProgramTypeRepository,
                        curriculumItemRepository,
                        formRepository,
                        formResponseHistoryRepository,
                        typeCd,
                        title,
                        leaderAndProposer,
                        List.of("1주차"));
        flushAndClear();
        return academicProgramRepository.findById(academicProgram.getId()).orElseThrow();
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

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
