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
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramTypeEntity;
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

    @Test
    void assignsTrackLeaderRoleForTrackType() {
        MemberEntity leader = saveMember("20260404", "트랙리더");
        AcademicProgramEntity academicProgram = createAcademicProgram("TRACK", "백엔드 트랙", leader);

        effectsService.applyPostApprovalEffects(academicProgram);
        flushAndClear();

        List<MemberRoleAssignmentEntity> assignments =
                memberRoleAssignmentRepository.findCurrentByMemberId(leader.getId());
        assertThat(assignments)
                .extracting(assignment -> assignment.getRole().getName())
                .contains("트랙장");
    }

    /*
     * **시드된 유형 전부가 이 자리를 지날 수 있어야 한다** (#510).
     *
     * 유형은 배포 없이 acdm_actv_type에 행을 더하는 것으로 늘어나는데(AcademicProgramTypeEntity
     * 주석) 리더 역할명은 이 서비스의 코드 상수다. 그래서 «유형만 늘고 맵은 그대로»가 가능하고,
     * 실제로 트랙에서 그 일이 일어났다 — 접수는 되는데 승인이 막혔다.
     *
     * 유형별 테스트를 하나씩 더하는 것으로는 막지 못한다. 새 유형을 더하는 사람이 그 테스트도
     * 함께 빠뜨리면 그만이기 때문이다. 그래서 목록을 **기준정보에서 읽어** 전수로 돈다 —
     * V13이 CHECK 제약과 enum을 전수 대조로 바꾼 것과 같은 판단이다. 시드에 유형을 더하고
     * 맵이나 role 시드를 빠뜨리면 여기서 깨진다.
     *
     * 역할명을 값으로 대조하지 않는 것은 그 표가 곧 검사 대상이기 때문이다 — 테스트에 옮겨
     * 적으면 두 벌이 되어 같이 틀린다. 여기서 보는 것은 «어떤 역할이든 붙었는가»다.
     */
    @Test
    void everySeededProgramTypeGetsItsLeaderRole() {
        List<String> typeCodes =
                academicProgramTypeRepository.findAllByOrderByDisplayOrderAsc().stream()
                        .map(AcademicProgramTypeEntity::getCode)
                        .toList();
        assertThat(typeCodes).isNotEmpty();

        for (int index = 0; index < typeCodes.size(); index++) {
            String typeCd = typeCodes.get(index);
            MemberEntity leader = saveMember("20269%03d".formatted(index), typeCd + " 리더");
            AcademicProgramEntity academicProgram =
                    createAcademicProgram(typeCd, typeCd + " 활동", leader);

            effectsService.applyPostApprovalEffects(academicProgram);
            flushAndClear();

            assertThat(memberRoleAssignmentRepository.findCurrentByMemberId(leader.getId()))
                    .as(
                            "%s 유형의 리더 역할 — 맵(LEADER_ROLE_NAME_BY_TYPE_CODE)과 role 시드가 함께 있어야 한다",
                            typeCd)
                    .isNotEmpty();
        }
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
