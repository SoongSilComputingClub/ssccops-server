package org.sscc.ssccopsserver.domain.academicprogram.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.service.FormService;
import org.sscc.ssccopsserver.domain.member.dto.MemberRoleAssignRequest;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.service.MemberRoleAssignmentService;

import lombok.RequiredArgsConstructor;

/*
 * 승인 후속 처리 구현 (#133).
 *
 * 규칙을 새로 만들지 않는다 — 역할 부여는 MemberRoleAssignmentService.assign(#81)을 그대로
 * 부른다. 다만 **이미 그 역할을 갖고 있으면 부여를 건너뛴다.**
 *
 * 이 자리가 assign()의 중복 거부(ROLE_ALREADY_ASSIGNED)를 그대로 물려받으면 안 되는 것은,
 * 두 호출부의 요구가 다르기 때문이다. 관리 화면의 수동 부여에서 중복은 실수이므로 막아야 하지만,
 * 승인 이관에서 중복은 정상이다 — 한 사람이 스터디를 둘 이상 이끄는 것은 막을 일이 아니고,
 * 역할 부여의 목적("이 사람이 스터디장 권한을 갖게 한다")은 이미 달성돼 있다. 그런데 실패로
 * 다루면 부작용 하나 때문에 승인 본체까지 롤백돼, 기획안이 아예 승인되지 않는다.
 *
 * 예외를 잡아 무시하지 않고 겹침을 먼저 묻는 것은, catch가 다른 이유로 난 같은 코드까지 삼키고
 * 무엇보다 "실패했지만 괜찮다"로 읽히기 때문이다 — 여기서 일어나는 일은 실패가 아니라 생략이다.
 *
 * 기존 역할에 종료일을 넣어 갈아 끼우지 않는다. 역할은 활동 단위가 아니라 사람 단위의 권한이고,
 * 이 자리는 어느 활동 때문에 부여됐는지를 알지 못한다 — 새 활동을 승인하면서 지난 배정을 끝내면
 * 아직 진행 중인 다른 활동의 근거를 지우게 된다.
 *
 * @Transactional은 REQUIRED(기본값)다 — 새 트랜잭션을 강제로 열지 않는다. #150(이관)이 이미
 * 연 트랜잭션 안에서 호출되면 그 트랜잭션에 참여하고, 이 이슈의 테스트처럼 단독으로 호출되면
 * 새로 연다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class AcademicProgramApprovalEffectsServiceImpl
        implements AcademicProgramApprovalEffectsService {

    /** 모집용 빈 폼 제목 접미. 화면이 "무엇을 위한 폼인지" 구분할 수 있어야 한다 */
    private static final String RECRUITMENT_FORM_TITLE_SUFFIX = " 모집";

    /*
     * 학술 활동 유형 → 리더 역할명. STUDY/PROJECT 2종은 data.sql이 시드하는 role_nm
     * "스터디장"/"프로젝트장"과 각각 대응한다(#130 유형 코드테이블, #71~ role 시드).
     *
     * type.getName() + "장" 같은 자동 유도를 쓰지 않는 것은 의도된 것이다 —
     * acdm_actv_type.type_nm은 관리 화면에서 바꿀 수 있는 값이라(AcademicProgramType
     * ServiceImpl.update), 코드 상수인 typeCd를 키로 둬야 이름이 바뀌어도 매핑이 흔들리지
     * 않는다. 새 유형(세미나 등)이 늘면 이 맵과 role 시드를 함께 늘려야 한다 — 매핑이 없는
     * typeCd는 missingSeed로 막는다.
     */
    private static final Map<String, String> LEADER_ROLE_NAME_BY_TYPE_CODE =
            Map.of(
                    "STUDY", "스터디장",
                    "PROJECT", "프로젝트장");

    private final MemberRoleRepository memberRoleRepository;
    private final MemberRoleAssignmentService memberRoleAssignmentService;
    private final MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    private final FormService formService;

    /* 겹침 판정의 기준일. assign(#81)과 같은 Clock을 봐야 두 판정이 어긋나지 않는다 */
    private final Clock clock;

    @Override
    public void applyPostApprovalEffects(AcademicProgramEntity academicProgram) {
        assignLeaderRole(academicProgram);
        linkRecruitmentForm(academicProgram);
    }

    /*
     * 리더 역할 부여. 이미 유효한 같은 역할이 있으면 아무것도 하지 않는다.
     *
     * 판정을 여기서 다시 쓰지 않고 assign()이 쓰는 것과 **같은 질의**를 부른다
     * (existsOverlappingAssignment) — 두 벌이 되면 "겹친다"의 뜻이 갈려, 이쪽은 건너뛰었는데
     * 저쪽은 거부하는(또는 그 반대의) 구간이 생긴다. 기준일도 assign()과 같은 '오늘'이다:
     * 새 배정은 언제나 [오늘, 무기한)이므로 종료일이 없거나 오늘 이후인 배정이 곧 겹치는 배정이다.
     */
    private void assignLeaderRole(AcademicProgramEntity academicProgram) {
        MemberRoleEntity role = findLeaderRole(academicProgram.getType().getCode());
        MemberEntity leader = academicProgram.getLeader();

        if (memberRoleAssignmentRepository.existsOverlappingAssignment(
                leader.getId(), role.getId(), LocalDate.now(clock))) {
            return;
        }

        memberRoleAssignmentService.assign(
                leader.getId(), new MemberRoleAssignRequest(role.getId(), null, null));
    }

    /*
     * 문항 0개인 DRAFT 폼을 만들어 Event에 연결한다. 생성자는 leader다 — 생성 시점부터
     * leader == proposer라 누구를 골라도 결과는 같지만, "이 활동을 이끄는 사람의 모집 폼"이라는
     * 의도가 더 잘 드러난다.
     */
    private void linkRecruitmentForm(AcademicProgramEntity academicProgram) {
        EventEntity event = academicProgram.getEvent();
        FormEntity form =
                formService.createEmptyDraft(
                        event.getTitle() + RECRUITMENT_FORM_TITLE_SUFFIX,
                        academicProgram.getLeader());
        event.linkForm(form);
    }

    private MemberRoleEntity findLeaderRole(String academicProgramTypeCode) {
        String roleName = LEADER_ROLE_NAME_BY_TYPE_CODE.get(academicProgramTypeCode);
        if (roleName == null) {
            throw missingSeed("리더 역할 매핑", academicProgramTypeCode);
        }
        return memberRoleRepository.findAllByNameOrderByIdAsc(roleName).stream()
                .findFirst()
                .orElseThrow(() -> missingSeed("리더 역할", roleName));
    }

    private static IllegalStateException missingSeed(String seedName, String code) {
        return new IllegalStateException("%s 시드가 없습니다: %s".formatted(seedName, code));
    }
}
