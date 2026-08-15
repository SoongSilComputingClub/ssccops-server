package org.sscc.ssccopsserver.domain.member.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.code.error.MemberErrorCode;
import org.sscc.ssccopsserver.domain.member.dto.MemberRoleAssignRequest;
import org.sscc.ssccopsserver.domain.member.dto.MemberRoleAssignmentResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberRoleUpdateRequest;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleAssignmentEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

/*
 * 회원 역할 배정의 구현 (#81).
 *
 * **규칙을 새로 만들지 않는 것이 이 클래스의 요점이다** (AuthorityAdminServiceImpl과 같은 태도).
 * 유효 역할 판정은 MemberRoleAssignmentRepository와 MemberRoleAssignmentEntity.isValidOn이 이미
 * 갖고 있는 BR-M25를 부르고, 자기 잠금 방지는 RoleManageSelfLockGuard를 부르기만 한다. 여기에
 * 같은 규칙을 한 벌 더 적으면 인가가 보는 '유효한 역할'과 이 화면이 다루는 배정이 갈린다.
 *
 * 세 가지 태도가 이 클래스를 관통한다:
 *
 *  1. **종료는 삭제가 아니다.** 어떤 경로로도 mbr_role_rel의 행을 지우지 않는다 — role_end_ymd를
 *     채워 과거로 만들 뿐이다. 지우면 "언제까지 국장이었는가"가 사라져 역할 삭제 가드(#79
 *     ROLE_IN_USE)가 지키려던 이력도 함께 없어진다.
 *  2. **부여·회수는 즉시 반영된다** (BR-M31). 인가 판정이 요청마다 DB를 보므로 세션을 무효화하는
 *     절차가 여기 없는 것은 필요가 없어서다 — 대상 회원은 재로그인 없이 다음 요청부터 달라진다.
 *  3. **대표 역할(rprs_role_yn)은 표시용이다** (BR-M26). 단일성을 지키는 것은 사이드바에 무엇을
 *     내걸지를 정하기 위해서이지 인가와는 무관하며, 이 API가 그 사실을 바꾸지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberRoleAssignmentServiceImpl implements MemberRoleAssignmentService {

    private final MemberRepository memberRepository;
    private final MemberRoleRepository memberRoleRepository;
    private final MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    private final RoleManageSelfLockGuard roleManageSelfLockGuard;
    private final Clock clock;

    /*
     * 목록. current=true는 인가가 보는 것과 **같은 질의**(findValidByMemberIds)를 쓴다 — 화면이
     * '현재 역할'이라고 부르는 것과 서버가 권한을 계산할 때 세는 것이 갈리면 "역할은 붙어 있는데
     * 403"이 된다.
     *
     * current=false는 종료된 배정까지 전부다. 행마다 실리는 current 플래그가 그 안에서 지금
     * 유효한 것을 가리키므로, 화면은 목록을 한 번만 받아 두 가지를 다 그릴 수 있다.
     *
     * 없는 회원은 빈 목록이 아니라 404다 — 목록이 비었다는 것과 그런 회원이 없다는 것은 화면이
     * 다르게 다뤄야 하는 답이다(역할 목록의 분류 필터가 빈 목록인 것과 갈리는 지점: 저쪽은
     * 조회 조건이고 이쪽은 자원의 소유자다).
     */
    @Override
    public List<MemberRoleAssignmentResponse> getAssignments(Long memberId, boolean currentOnly) {
        findMember(memberId);
        LocalDate today = today();

        List<MemberRoleAssignmentEntity> assignments =
                currentOnly
                        ? memberRoleAssignmentRepository.findValidByMemberIds(
                                List.of(memberId), today)
                        : memberRoleAssignmentRepository.findAllByMemberId(memberId);

        return assignments.stream()
                .map(assignment -> MemberRoleAssignmentResponse.of(assignment, today))
                .toList();
    }

    /*
     * 부여.
     *
     * **자기 잠금 방지를 걸지 않는다.** 역할을 주는 조작은 어떤 회원의 권한도 좁히지 못하므로
     * 요청자가 이것으로 ROLE_MANAGE를 잃을 수 없다. 가드는 인가를 좁히는 문(종료·기간 단축)에만
     * 선다 — 넓히는 쪽에 세워 두면 매 부여마다 판정 질의가 한 벌 더 붙을 뿐이다.
     *
     * 시작일을 생략하면 오늘이며, 과거 날짜도 받는다. 이미 맡고 있던 역할을 뒤늦게 반영하는 것이
     * 이관 초기의 정상적인 조작이기 때문이다.
     */
    @Override
    @Transactional
    public MemberRoleAssignmentResponse assign(Long memberId, MemberRoleAssignRequest request) {
        MemberEntity member = findMember(memberId);
        MemberRoleEntity role = findRole(request.roleId());
        LocalDate today = today();
        LocalDate startDate = request.roleBgngYmd() == null ? today : request.roleBgngYmd();

        if (memberRoleAssignmentRepository.existsOverlappingAssignment(
                memberId, role.getId(), startDate)) {
            throw new GeneralException(MemberErrorCode.ROLE_ALREADY_ASSIGNED);
        }

        boolean representative = Boolean.TRUE.equals(request.rprsRoleYn());
        MemberRoleAssignmentEntity assignment =
                MemberRoleAssignmentEntity.create(member, role, startDate, representative);

        /*
         * 대표를 내리는 것은 새 배정이 **지금 유효할 때만**이다. 아직 시작하지 않은 배정을
         * 대표로 지정했다고 오늘의 대표를 미리 내리면, 시작일까지 사이드바에 아무 역할도 걸리지
         * 않는 빈 구간이 생긴다. 단일성은 '유효한 것 중 최대 1건'이므로 그 사이 둘이 되지도 않는다.
         */
        if (representative && assignment.isValidOn(today)) {
            demoteOtherRepresentatives(memberId, null, today);
        }

        return MemberRoleAssignmentResponse.of(
                memberRoleAssignmentRepository.saveAndFlush(assignment), today);
    }

    /*
     * 종료일·대표 여부 변경. 본문에 없는(null인) 필드는 건드리지 않는다.
     *
     * **자기 잠금 방지 가드가 마지막에 선다** (VR-M13). 역할 종료·기간 단축은 #65가 역할↔권한
     * 교체에 세운 가드를 우회하는 두 번째 문이다 — 권한을 그대로 둔 채 사람에게서 역할을 떼면
     * 결과는 같기 때문이다. 판정은 복제하지 않고 RoleManageSelfLockGuard를 부른다.
     *
     * 대표 여부만 바꾸는 요청에도 가드를 태우는 것은 분기를 하나 줄이려는 것이 아니라, "무엇이
     * 인가를 좁히는 변경인가"를 여기서 다시 판단하지 않기 위해서다. 애스펙트가 이미 요청 시작
     * 시점에 ROLE_MANAGE를 확인했으므로 여기서 false가 나올 수 있는 원인은 이 트랜잭션의 변경
     * 하나뿐이다.
     */
    @Override
    @Transactional
    public MemberRoleAssignmentResponse updateAssignment(
            Long memberId, Long assignmentId, MemberRoleUpdateRequest request, Long requesterId) {

        findMember(memberId);
        MemberRoleAssignmentEntity assignment =
                memberRoleAssignmentRepository
                        .findByIdAndMemberId(assignmentId, memberId)
                        .orElseThrow(
                                () ->
                                        new GeneralException(
                                                MemberErrorCode.MEMBER_ROLE_ASSIGNMENT_NOT_FOUND));

        LocalDate today = today();

        if (request.roleEndYmd() != null) {
            if (request.roleEndYmd().isBefore(assignment.getRoleStartDate())) {
                throw new GeneralException(MemberErrorCode.ROLE_PERIOD_INVALID);
            }
            assignment.end(request.roleEndYmd());
        }

        if (request.rprsRoleYn() != null) {
            if (Boolean.TRUE.equals(request.rprsRoleYn())) {
                demoteOtherRepresentatives(memberId, assignmentId, today);
            }
            assignment.changeRepresentative(request.rprsRoleYn());
        }

        roleManageSelfLockGuard.verifyRequesterKeepsRoleManage(requesterId);

        return MemberRoleAssignmentResponse.of(assignment, today);
    }

    // ------------------------------------------------------------------ 헬퍼

    /*
     * 지금 유효한 다른 대표 역할을 내린다 (회원당 최대 1건).
     *
     * 한 건만 찾아 내리지 않고 목록을 도는 것은 이 API 이전에 들어간 데이터에 대표가 둘 이상
     * 있을 수 있어서다 — 하나만 내리면 나머지가 남아 단일성이 영영 회복되지 않는다.
     * 이미 끝난 임기에 남아 있는 rprs_role_yn은 건드리지 않는다. 지난 이력을 오늘의 조작으로
     * 고쳐 쓰지 않는 것이 '종료는 삭제가 아니다'와 같은 태도다.
     */
    private void demoteOtherRepresentatives(Long memberId, Long keepAssignmentId, LocalDate today) {
        for (MemberRoleAssignmentEntity representative :
                memberRoleAssignmentRepository.findValidRepresentatives(memberId, today)) {

            if (!representative.getId().equals(keepAssignmentId)) {
                representative.changeRepresentative(false);
            }
        }
    }

    private MemberEntity findMember(Long memberId) {
        return memberRepository
                .findById(memberId)
                .orElseThrow(() -> new GeneralException(MemberErrorCode.MEMBER_NOT_FOUND));
    }

    private MemberRoleEntity findRole(Long roleId) {
        return memberRoleRepository
                .findById(roleId)
                .orElseThrow(() -> new GeneralException(MemberErrorCode.ROLE_NOT_FOUND));
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }
}
