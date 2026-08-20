package org.sscc.ssccopsserver.domain.member.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.code.error.MemberErrorCode;
import org.sscc.ssccopsserver.domain.member.dto.AuthorityCreateRequest;
import org.sscc.ssccopsserver.domain.member.dto.AuthorityResponse;
import org.sscc.ssccopsserver.domain.member.dto.AuthorityTreeResponse;
import org.sscc.ssccopsserver.domain.member.dto.AuthorityUpdateRequest;
import org.sscc.ssccopsserver.domain.member.dto.RoleAuthorityResponse;
import org.sscc.ssccopsserver.domain.member.dto.RoleAuthorityResponse.RoleAuthorityGrant;
import org.sscc.ssccopsserver.domain.member.entity.AuthorityEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleEntity;
import org.sscc.ssccopsserver.domain.member.entity.RoleAuthorityRelationEntity;
import org.sscc.ssccopsserver.domain.member.repository.AuthorityRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.RoleAuthorityRelationRepository;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

/*
 * 권한 트리·역할 부여 관리의 구현 (#65).
 *
 * **규칙을 새로 만들지 않는 것이 이 클래스의 요점이다.** 순환 검사는 AuthorityEntity.changeParent,
 * 자손 펼침은 AuthorityPolicy.expandOf가 이미 갖고 있고 여기서는 부르기만 한다 — 관리 쪽에
 * 같은 규칙을 한 벌 더 적으면 화면에서 만든 트리와 인가가 보는 트리가 갈린다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthorityAdminServiceImpl implements AuthorityAdminService {

    /** indctSeqno를 생략했을 때의 기본 표시 순번. 형제 뒤쪽으로 밀어 둔다 */
    private static final short DEFAULT_DISPLAY_ORDER = 99;

    private final AuthorityRepository authorityRepository;
    private final RoleAuthorityRelationRepository roleAuthorityRelationRepository;
    private final MemberRoleRepository memberRoleRepository;
    private final AuthorityPolicy authorityPolicy;
    private final RoleManageSelfLockGuard roleManageSelfLockGuard;
    private final EntityManager entityManager;

    /*
     * 권한 전량을 질의 한 번으로 받아 메모리에서 트리로 엮는다. 노드마다 자식을 다시 조회하면
     * 그대로 N+1이고, 재귀 CTE는 H2(test)와 PostgreSQL 양쪽에서 같은 SQL을 보장하기 어렵다
     * (AuthorityRepository.findAllLinks와 같은 판단).
     *
     * 부모가 없는 노드뿐 아니라 **부모 코드가 목록에 없는 노드도 최상위로 올린다.** 정상 데이터에서는
     * FK가 있어 일어나지 않지만, 그렇게 두지 않으면 데이터가 깨졌을 때 노드가 응답에서 통째로
     * 사라져 화면에서 고칠 수조차 없게 된다.
     */
    @Override
    public List<AuthorityTreeResponse> getAuthorityTree() {
        List<AuthorityEntity> authorities = authorityRepository.findAllForTree();

        Map<String, List<AuthorityEntity>> childrenByParent = new LinkedHashMap<>();
        List<AuthorityEntity> roots = new ArrayList<>();
        Set<String> knownCodes =
                authorities.stream().map(AuthorityEntity::getCode).collect(Collectors.toSet());

        for (AuthorityEntity authority : authorities) {
            String parentCode = parentCodeOf(authority);
            if (parentCode == null || !knownCodes.contains(parentCode)) {
                roots.add(authority);
            } else {
                childrenByParent
                        .computeIfAbsent(parentCode, key -> new ArrayList<>())
                        .add(authority);
            }
        }

        return roots.stream().map(root -> toTree(root, childrenByParent)).toList();
    }

    /*
     * 사용자 정의 권한 생성. sys_yn은 요청에서 받지 않고 항상 false다 (BR-M32).
     *
     * save()가 아니라 persist()인 것은 코드가 IDENTITY가 아니라 직접 넣는 PK이기 때문이다.
     * Spring Data의 save()는 식별자가 채워져 있으면 merge()로 도는데, merge는 SELECT 후 행이
     * 있으면 UPDATE로 넘어간다 — 같은 코드를 동시에 만들려는 두 요청 중 진 쪽이 409가 아니라
     * 남의 권한을 조용히 덮어쓰게 된다. persist는 그 경우 제약 위반으로 드러난다.
     */
    @Override
    @Transactional
    public AuthorityResponse createAuthority(AuthorityCreateRequest request) {
        String code = request.authrtCd().trim();
        if (authorityRepository.existsById(code)) {
            throw new GeneralException(MemberErrorCode.AUTHORITY_CODE_DUPLICATED);
        }

        AuthorityEntity parent = findParentOrNull(request.upAuthrtCd());
        AuthorityEntity created =
                AuthorityEntity.create(
                        code,
                        request.authrtNm().trim(),
                        parent,
                        trimToNull(request.authrtExpln()),
                        false,
                        displayOrderOf(request.indctSeqno(), DEFAULT_DISPLAY_ORDER));

        try {
            entityManager.persist(created);
            entityManager.flush();
        } catch (DataIntegrityViolationException | PersistenceException ex) {
            // 선조회를 나란히 통과한 동시 요청은 제약 위반으로만 드러난다 — 같은 409로 옮긴다
            throw new GeneralException(MemberErrorCode.AUTHORITY_CODE_DUPLICATED);
        }
        return AuthorityResponse.from(created);
    }

    /*
     * 이름·설명·상위·표시 순번 변경.
     *
     * 상위를 먼저 바꾼다 — 순환이면 changeParent가 던지므로 이름도 바뀌지 않는다. 실패한 요청이
     * 절반만 반영되지 않게 하는 순서다(트랜잭션도 되돌리지만, 같은 요청 안에서 두 번 읽는 값이
     * 어긋나는 것을 애초에 만들지 않는다).
     *
     * sys_yn = true여도 이름·설명·트리 위치는 바꿀 수 있다 (BR-M33). 막히는 것은 삭제와 코드뿐이다 —
     * 조직이 부르는 이름은 바뀌는데 코드가 참조하는 값은 그대로여야 하기 때문이다.
     */
    @Override
    @Transactional
    public AuthorityResponse updateAuthority(String authrtCd, AuthorityUpdateRequest request) {
        AuthorityEntity authority = findAuthority(authrtCd);
        rejectCodeChange(authority, request.authrtCd());

        // 순환 검사는 여기서 다시 하지 않는다 — AuthorityEntity가 조상을 거슬러 올라가며 막는다 (#9)
        authority.changeParent(findParentOrNull(request.upAuthrtCd()));
        authority.updateDescription(
                request.authrtNm().trim(),
                trimToNull(request.authrtExpln()),
                displayOrderOf(request.indctSeqno(), authority.getDisplayOrder()));

        return AuthorityResponse.from(authority);
    }

    /*
     * 삭제. 세 가지를 차례로 막는다 — 시스템 권한(409 SYSTEM_AUTHORITY_IMMUTABLE), 어느
     * 역할엔가 부여된 권한(409 AUTHORITY_IN_USE), 자식이 달린 권한(같은 409).
     *
     * 부여된 권한을 지울 때 관계까지 함께 지우지 않는 것은 의도된 것이다. 그렇게 두면 삭제 한
     * 번으로 여러 역할의 인가 범위가 조용히 줄어든다 — 회수를 먼저 하게 해서 무엇이 사라지는지
     * 화면에서 보이게 한다.
     */
    @Override
    @Transactional
    public void deleteAuthority(String authrtCd) {
        AuthorityEntity authority = findAuthority(authrtCd);

        if (authority.isSystemDefined()) {
            throw new GeneralException(MemberErrorCode.SYSTEM_AUTHORITY_IMMUTABLE);
        }
        if (roleAuthorityRelationRepository.existsByAuthority(authority)
                || authorityRepository.existsByParent(authority)) {
            throw new GeneralException(MemberErrorCode.AUTHORITY_IN_USE);
        }

        authorityRepository.delete(authority);
    }

    @Override
    public RoleAuthorityResponse getRoleAuthorities(Long roleId) {
        MemberRoleEntity role = findRole(roleId);
        return toRoleAuthorityResponse(
                role, roleAuthorityRelationRepository.findAllByRoleId(roleId));
    }

    /*
     * 역할의 권한 전체 교체 (BR-M30).
     *
     * 폼 라벨 지정 교체(#34)와 같은 방식으로 **차집합만 움직인다.** 통째로 지우고 다시 넣는 쪽이
     * 짧지만 그러면 유지되는 부여의 crt_dt가 저장할 때마다 갱신돼 "언제 이 권한이 붙었는가"를
     * 잃는다. 같은 (role_id, authrt_cd)를 한 트랜잭션에서 지웠다 넣으면 Hibernate가 INSERT를
     * DELETE보다 먼저 흘려보내 UNIQUE 제약에 걸리기도 한다.
     *
     * **자기 잠금 방지는 바꾼 뒤에 정책에게 다시 물어보는 방식이며, 그 판정은 여기 없다**
     * (VR-M13). RoleManageSelfLockGuard 한 곳에 있고 회원 역할 종료(#81)도 같은 것을 부른다 —
     * 인가를 좁히는 문이 둘인데 잠금 장치가 둘이면 한쪽만 고쳐진 규칙이 우회로가 된다.
     */
    @Override
    @Transactional
    public RoleAuthorityResponse replaceRoleAuthorities(
            Long roleId, List<String> authrtCds, Long requesterMemberId) {

        MemberRoleEntity role = findRole(roleId);

        // 같은 코드가 두 번 실려 와도 한 번으로 본다 — 화면 실수가 UNIQUE 위반으로 번지지 않게 한다
        Set<String> requested =
                authrtCds == null
                        ? Set.of()
                        : authrtCds.stream()
                                .map(AuthorityAdminServiceImpl::trimToNull)
                                .filter(code -> code != null)
                                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<RoleAuthorityRelationEntity> existing =
                roleAuthorityRelationRepository.findAllByRoleId(roleId);
        Set<String> existingCodes =
                existing.stream()
                        .map(relation -> relation.getAuthority().getCode())
                        .collect(Collectors.toCollection(LinkedHashSet::new));

        List<RoleAuthorityRelationEntity> removed =
                existing.stream()
                        .filter(relation -> !requested.contains(relation.getAuthority().getCode()))
                        .toList();
        if (!removed.isEmpty()) {
            roleAuthorityRelationRepository.deleteAllInBatch(removed);
        }

        List<String> addedCodes =
                requested.stream().filter(code -> !existingCodes.contains(code)).toList();
        List<RoleAuthorityRelationEntity> added = grant(role, addedCodes);

        List<RoleAuthorityRelationEntity> kept =
                existing.stream()
                        .filter(relation -> requested.contains(relation.getAuthority().getCode()))
                        .toList();

        roleManageSelfLockGuard.verifyRequesterKeepsRoleManage(requesterMemberId);

        List<RoleAuthorityRelationEntity> result = new ArrayList<>(kept);
        result.addAll(added);
        result.sort(Comparator.comparing(relation -> relation.getAuthority().getCode()));

        return toRoleAuthorityResponse(role, result);
    }

    // ------------------------------------------------------------------ 헬퍼

    private List<RoleAuthorityRelationEntity> grant(MemberRoleEntity role, List<String> codes) {
        if (codes.isEmpty()) {
            return List.of();
        }

        List<AuthorityEntity> authorities = authorityRepository.findAllById(codes);
        // findAllById는 없는 식별자를 조용히 건너뛴다 — 개수 차이가 곧 '존재하지 않는 권한'이다
        if (authorities.size() != codes.size()) {
            throw new GeneralException(MemberErrorCode.AUTHORITY_NOT_FOUND);
        }

        return roleAuthorityRelationRepository.saveAllAndFlush(
                authorities.stream()
                        .map(authority -> RoleAuthorityRelationEntity.create(role, authority))
                        .toList());
    }

    private RoleAuthorityResponse toRoleAuthorityResponse(
            MemberRoleEntity role, List<RoleAuthorityRelationEntity> relations) {

        List<String> grantedCodes =
                relations.stream().map(relation -> relation.getAuthority().getCode()).toList();

        return new RoleAuthorityResponse(
                role.getId(),
                role.getName(),
                relations.stream().map(RoleAuthorityGrant::from).toList(),
                authorityPolicy.expandOf(grantedCodes).stream().sorted().toList());
    }

    private AuthorityTreeResponse toTree(
            AuthorityEntity authority, Map<String, List<AuthorityEntity>> childrenByParent) {

        List<AuthorityTreeResponse> children =
                childrenByParent.getOrDefault(authority.getCode(), List.of()).stream()
                        .map(child -> toTree(child, childrenByParent))
                        .toList();

        return AuthorityTreeResponse.of(authority, children);
    }

    private AuthorityEntity findAuthority(String authrtCd) {
        return authorityRepository
                .findById(authrtCd)
                .orElseThrow(() -> new GeneralException(MemberErrorCode.AUTHORITY_NOT_FOUND));
    }

    private AuthorityEntity findParentOrNull(String upAuthrtCd) {
        String code = trimToNull(upAuthrtCd);
        return code == null ? null : findAuthority(code);
    }

    private MemberRoleEntity findRole(Long roleId) {
        return memberRoleRepository
                .findById(roleId)
                .orElseThrow(() -> new GeneralException(MemberErrorCode.ROLE_NOT_FOUND));
    }

    /*
     * 코드 변경 시도의 거절. 본문에 authrtCd가 없거나 경로와 같으면 아무 일도 하지 않는다.
     *
     * 시스템 권한과 사용자 정의 권한의 코드를 나누는 것은 막다른 길인지 아닌지가 다르기 때문이다
     * (MemberErrorCode 주석 참고).
     */
    /*
     * 권한 코드 → 표시명 (#123). 판정이 아니라 표시용 조회라 AuthorityPolicy가 아니라 여기에
     * 둔다 — 저 클래스는 "무엇을 할 수 있는가"만 알아야 한다(BR-M28).
     */
    @Override
    public Map<String, String> authorityNamesOf(Collection<String> authrtCds) {
        if (authrtCds == null || authrtCds.isEmpty()) {
            return Map.of();
        }
        return authorityRepository.findAllById(authrtCds).stream()
                .collect(Collectors.toMap(AuthorityEntity::getCode, AuthorityEntity::getName));
    }

    private static void rejectCodeChange(AuthorityEntity authority, String requestedCode) {
        String code = trimToNull(requestedCode);
        if (code == null || code.equals(authority.getCode())) {
            return;
        }
        throw new GeneralException(
                authority.isSystemDefined()
                        ? MemberErrorCode.SYSTEM_AUTHORITY_IMMUTABLE
                        : MemberErrorCode.AUTHORITY_CODE_IMMUTABLE);
    }

    private static String parentCodeOf(AuthorityEntity authority) {
        return authority.getParent() == null ? null : authority.getParent().getCode();
    }

    private static short displayOrderOf(Short requested, Short fallback) {
        if (requested != null) {
            return requested;
        }
        return fallback == null ? DEFAULT_DISPLAY_ORDER : fallback;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
