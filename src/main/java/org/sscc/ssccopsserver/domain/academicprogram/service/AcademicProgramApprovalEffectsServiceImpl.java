package org.sscc.ssccopsserver.domain.academicprogram.service;

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
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.service.MemberRoleAssignmentService;

import lombok.RequiredArgsConstructor;

/*
 * 승인 후속 처리 구현 (#133).
 *
 * 규칙을 새로 만들지 않는다 — 역할 부여는 MemberRoleAssignmentService.assign(#81)을 그대로
 * 부른다. 겹치는 기간에 같은 역할을 두 번 주지 않는 규칙(ROLE_ALREADY_ASSIGNED)도 그대로
 * 적용된다: 어떤 회원이 이미 유효한 스터디장/팀장 역할을 갖고 있는 채로 새 활동의 리더가 되면
 * 이 호출이 409로 실패하고 전체 승인 후속 처리(그리고 #150의 이관 자체)가 롤백된다 — 지금은
 * 그 시나리오(한 사람이 동시에 여러 활동을 이끄는 경우)의 운영 처리가 별도로 정의돼 있지 않아,
 * 조용히 건너뛰지 않고 실패를 드러내는 쪽을 택했다.
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
     * academic_program_type.type_nm은 관리 화면에서 바꿀 수 있는 값이라(AcademicProgramType
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
    private final FormService formService;

    @Override
    public void applyPostApprovalEffects(AcademicProgramEntity academicProgram) {
        assignLeaderRole(academicProgram);
        linkRecruitmentForm(academicProgram);
    }

    private void assignLeaderRole(AcademicProgramEntity academicProgram) {
        MemberRoleEntity role = findLeaderRole(academicProgram.getType().getCode());
        MemberEntity leader = academicProgram.getLeader();

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
