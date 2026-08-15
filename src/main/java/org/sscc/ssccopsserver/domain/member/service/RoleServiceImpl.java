package org.sscc.ssccopsserver.domain.member.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.code.error.MemberErrorCode;
import org.sscc.ssccopsserver.domain.member.dto.RoleCreateRequest;
import org.sscc.ssccopsserver.domain.member.dto.RoleDetailResponse;
import org.sscc.ssccopsserver.domain.member.dto.RoleResponse;
import org.sscc.ssccopsserver.domain.member.dto.RoleUpdateRequest;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleClassificationEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleClassificationRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.RoleAuthorityRelationRepository;
import org.sscc.ssccopsserver.domain.member.repository.RoleMemberCount;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

/*
 * 역할 마스터 관리의 구현 (#79).
 *
 * ============================ 이름 중복 정책 ============================
 *
 * **같은 이름의 역할은 애플리케이션에서 거절한다** (409 ROLE_NAME_DUPLICATED). 데이터사전의
 * role_nm은 Not Null = N이고 UNIQUE도 아니므로 DB는 이것을 막지 않는다. 그럼에도 막는 근거는
 * 두 가지다:
 *
 *  1. **data.sql이 역할명으로 멱등 판정과 권한 매핑을 한다** (`WHERE r.role_nm = '국장'`).
 *     같은 이름의 역할이 둘 있으면 시드가 **양쪽 모두에** 권한을 붙인다 — 화면에서 만든 역할이
 *     재기동 한 번으로 국장의 권한을 통째로 받는다는 뜻이며, 이것은 인가 사고다. 부트스트랩
 *     역할 조회(findAllByNameForUpdate, #71)도 같은 이름 공간을 쓴다.
 *  2. 운영자가 목록에서 어느 쪽이 어느 쪽인지 구별할 수 없다. 역할 부여 화면의 드롭다운에는
 *     '국장'이 두 줄 뜨고 어느 것을 골라야 하는지 알려 줄 값이 없다.
 *
 * **DB에 UNIQUE 제약을 새로 걸지는 않았다.** 걸면 데이터사전(SSoT)과 어긋나므로 사전을 먼저
 * 고쳐야 하고, 그것은 이 이슈의 범위가 아니다(운영 DB에 이미 중복이 있다면 제약 추가 자체가
 * 배포를 실패시킨다는 문제도 있다). 그 대가로 **선조회와 INSERT 사이의 좁은 창은 남는다** —
 * 같은 이름의 생성 요청 두 건이 정확히 동시에 들어오면 둘 다 통과한다. 하위 업무 유형(#70)이나
 * 학번(#21)처럼 UNIQUE 위반을 같은 409로 옮기는 방어가 여기에 없는 것은 걸 제약이 없기
 * 때문이다. 역할 생성은 운영진 한 사람이 화면에서 어쩌다 한 번 하는 조작이라 이 창을 감수한다.
 * 사전이 UNIQUE를 등재하면 그때 saveAndFlush를 try/catch로 감싸 같은 코드로 옮기면 된다.
 *
 * ========================================================================
 *
 * indct_seqno는 **분류 안의 표시 순번이며 서열이 아니다** (VR-M11). 정렬 외의 판정에 쓰지 않으며,
 * 자동 채번도 분류 안에서만 최대값을 찾는다 — 분류를 가르지 않고 매기면 '프로젝트장(PROJECT 1)'이
 * '국장(POSITION 4)'보다 높게 계산되는 그 오해를 데이터가 거들게 된다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RoleServiceImpl implements RoleService {

    private final MemberRoleRepository memberRoleRepository;
    private final MemberRoleClassificationRepository memberRoleClassificationRepository;
    private final MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    private final RoleAuthorityRelationRepository roleAuthorityRelationRepository;
    private final Clock clock;

    /*
     * 역할 목록. **쿼리는 역할 수와 무관하게 두 번이다** — 역할(분류 join fetch) 1 + 보유 회원 수
     * 집계 1. 역할마다 count를 돌리면 그대로 N+1이고, 역할은 화면에서 계속 늘어나는 데이터다.
     *
     * 페이징을 두지 않는다. 역할은 기준 데이터라 수십 건 규모이고 관리 화면도 역할 부여 화면의
     * 드롭다운도 전량을 한 번에 그린다(SubWorkType·권한 트리와 같은 판단).
     *
     * 없는 분류 코드로 걸러도 404가 아니라 빈 목록이다 — 필터는 자원이 아니라 조회 조건이며,
     * 목록이 비었다는 것과 분류가 없다는 것을 화면이 다르게 다룰 이유가 없다.
     */
    @Override
    public List<RoleResponse> getRoles(String roleClsfCd) {
        String classificationCode = trimToNull(roleClsfCd);
        List<MemberRoleEntity> roles =
                classificationCode == null
                        ? memberRoleRepository.findAllForList()
                        : memberRoleRepository.findAllForListByClassification(classificationCode);
        if (roles.isEmpty()) {
            return List.of();
        }

        Map<Long, Long> memberCounts =
                currentMemberCounts(roles.stream().map(MemberRoleEntity::getId).toList());

        return roles.stream()
                .map(role -> RoleResponse.of(role, memberCounts.getOrDefault(role.getId(), 0L)))
                .toList();
    }

    @Override
    public RoleDetailResponse getRole(Long roleId) {
        MemberRoleEntity role = findRole(roleId);
        return RoleDetailResponse.of(
                role, memberRoleAssignmentRepository.findCurrentByRoleId(roleId, today()));
    }

    /*
     * 생성. 갓 만든 역할에는 권한도 재임자도 없다 — "권한 없는 역할은 아무것도 못 한다"가
     * 기본값이므로(#9) 여기서 어떤 권한도 함께 붙이지 않는다. 권한은 역할 관리 화면에서
     * PUT /v1/roles/{roleId}/authorities로 따로 붙인다.
     */
    @Override
    @Transactional
    public RoleResponse createRole(RoleCreateRequest request) {
        String name = request.roleNm().trim();
        if (memberRoleRepository.existsByName(name)) {
            throw new GeneralException(MemberErrorCode.ROLE_NAME_DUPLICATED);
        }

        MemberRoleClassificationEntity classification = findClassification(request.roleClsfCd());
        int displayOrder = displayOrderOf(request.indctSeqno(), classification.getCode());

        MemberRoleEntity role =
                memberRoleRepository.saveAndFlush(
                        MemberRoleEntity.create(displayOrder, name, classification));

        // 새 역할에 재임자가 있을 수 없으므로 집계 질의를 돌리지 않는다
        return RoleResponse.of(role, 0L);
    }

    /*
     * 이름·분류·표시 순번 변경. null인 필드는 건드리지 않는다(RoleUpdateRequest 주석 참고).
     *
     * **분류만 바꾸고 순번을 생략하면 순번을 새 분류 기준으로 다시 매긴다.** 옛 분류에서 4번이던
     * 역할이 그 값을 들고 새 분류로 넘어가면, 그 분류에 이미 4번이 있으면 나란히 서고 없으면
     * 1·2 다음에 빈 자리를 두고 선다 — 어느 쪽이든 '분류 안에서 1부터 이어지는 표시 순번'이라는
     * 성질이 깨진다. 순번을 명시했다면 그 값을 그대로 쓴다.
     */
    @Override
    @Transactional
    public RoleResponse updateRole(Long roleId, RoleUpdateRequest request) {
        MemberRoleEntity role = findRole(roleId);

        String name = request.roleNm() == null ? role.getName() : request.roleNm().trim();
        if (!Objects.equals(name, role.getName())
                && memberRoleRepository.existsByNameAndIdNot(name, roleId)) {
            throw new GeneralException(MemberErrorCode.ROLE_NAME_DUPLICATED);
        }

        MemberRoleClassificationEntity classification = role.getRoleClassification();
        boolean classificationChanged =
                request.roleClsfCd() != null
                        && !request.roleClsfCd().trim().equals(classification.getCode());
        if (classificationChanged) {
            classification = findClassification(request.roleClsfCd());
        }

        Integer displayOrder = request.indctSeqno();
        if (displayOrder == null) {
            displayOrder =
                    classificationChanged
                            ? nextDisplayOrder(classification.getCode())
                            : role.getDisplayOrder();
        }

        role.update(displayOrder, name, classification);
        memberRoleRepository.flush();

        return RoleResponse.of(role, currentMemberCounts(List.of(roleId)).getOrDefault(roleId, 0L));
    }

    /*
     * 삭제는 좁게 연다. 배정 이력이 **하나라도**(종료된 것 포함) 있거나 권한이 붙어 있으면
     * 409 ROLE_IN_USE다.
     *
     * 참조를 함께 지워 주지 않는 것은 의도된 것이다 — 배정 이력을 지우면 "그 사람이 언제
     * 국장이었는지"가 사라지고, 권한 매핑을 지우면 삭제 한 번으로 인가 범위가 조용히 바뀐다
     * (#65 AUTHORITY_IN_USE와 같은 태도). 쓰이던 역할을 목록에서 감추는 길은 지금 없다 —
     * 데이터사전의 role에 use_yn이 없어서이며, 여기에 임의로 만들지 않는다.
     */
    @Override
    @Transactional
    public void deleteRole(Long roleId) {
        MemberRoleEntity role = findRole(roleId);

        if (memberRoleAssignmentRepository.existsByRoleId(roleId)
                || roleAuthorityRelationRepository.existsByRoleId(roleId)) {
            throw new GeneralException(MemberErrorCode.ROLE_IN_USE);
        }

        memberRoleRepository.delete(role);
    }

    // ------------------------------------------------------------------ 헬퍼

    private Map<Long, Long> currentMemberCounts(List<Long> roleIds) {
        Map<Long, Long> counts = new HashMap<>();
        for (RoleMemberCount row :
                memberRoleAssignmentRepository.countCurrentMembersByRoleIds(roleIds, today())) {
            counts.put(row.getRoleId(), row.getMemberCount());
        }
        return counts;
    }

    private MemberRoleEntity findRole(Long roleId) {
        return memberRoleRepository
                .findByIdWithClassification(roleId)
                .orElseThrow(() -> new GeneralException(MemberErrorCode.ROLE_NOT_FOUND));
    }

    private MemberRoleClassificationEntity findClassification(String roleClsfCd) {
        return memberRoleClassificationRepository
                .findById(roleClsfCd.trim())
                .orElseThrow(
                        () -> new GeneralException(MemberErrorCode.ROLE_CLASSIFICATION_NOT_FOUND));
    }

    private int displayOrderOf(Integer requested, String roleClsfCd) {
        return requested == null ? nextDisplayOrder(roleClsfCd) : requested;
    }

    /** 같은 분류 안의 최대값 + 1. 그 분류의 첫 역할이면 1이다 */
    private int nextDisplayOrder(String roleClsfCd) {
        return memberRoleRepository.findMaxDisplayOrderByClassification(roleClsfCd) + 1;
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
