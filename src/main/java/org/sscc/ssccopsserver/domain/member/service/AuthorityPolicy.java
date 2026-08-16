package org.sscc.ssccopsserver.domain.member.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.repository.AuthorityLink;
import org.sscc.ssccopsserver.domain.member.repository.AuthorityRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.RoleAuthorityRelationRepository;

import lombok.RequiredArgsConstructor;

/*
 * **"이 회원이 무엇을 할 수 있는가"의 유일한 구현** (#9 · ssccops#68).
 *
 * 판정 경로는 하나다: 회원 → 유효한 역할 → 그 역할들에 부여된 권한 → 자손까지 펼침.
 *
 * 인가 애스펙트(@RequireAuthority)와 세션 응답의 capabilities가 **같은 메서드**를 쓴다.
 * 두 벌로 두면 "버튼은 보이는데 누르면 403"이나 그 반대가 조용히 생긴다 — 폼 도메인에서
 * 라벨 규칙이 두 경로로 갈려 실제 버그가 났던 전례가 있어 처음부터 한 곳에 둔다 (BR-M28).
 * hasAuthority가 capabilities를 그대로 계산해 포함 여부만 보는 것도 같은 이유다. 두 판정이
 * 어긋날 자리 자체를 만들지 않는다.
 *
 * 규칙 세 가지를 여기서만 안다:
 *  - 유효한 역할: role_bgng_ymd <= 오늘 <= role_end_ymd (종료일 NULL이면 무기한, BR-M25).
 *    오늘은 주입된 Clock에서 온다 — LocalDate.now()를 직접 부르면 만료 판정을 테스트에서
 *    고정할 수 없다.
 *  - 펼침은 위→아래 한 방향 (BR-M22). 상위를 가지면 자손 전부를 갖지만, 자손을 가졌다고
 *    상위가 생기지 않는다.
 *  - 대표 역할(rprs_role_yn)은 보지 않는다 (BR-M26). 여러 역할 중 하나라도 만족하면 통과다.
 *
 * 지연 판정이다 — @RequireAuthority가 붙은 요청에서만 조회가 일어난다. 인증 시점에 모든
 * 회원의 권한을 GrantedAuthority로 굳히면 권한이 필요 없는 요청에도 쿼리가 붙는다.
 */
@Service
@RequiredArgsConstructor
public class AuthorityPolicy {

    private final MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    private final RoleAuthorityRelationRepository roleAuthorityRelationRepository;
    private final AuthorityRepository authorityRepository;
    private final Clock clock;

    /*
     * 회원이 실제로 행사할 수 있는 권한 코드 전부(펼친 결과).
     *
     * 정렬 없이 Set으로 돌려준다 — 순서에 의미가 없고, 응답에 실을 때만 정렬한다.
     * 역할이 없거나 역할에 권한이 하나도 붙어 있지 않으면 빈 집합이다. "권한 없는 새 역할은
     * 아무것도 못 한다"가 기본값이라 여기서 어떤 기본 권한도 얹지 않는다.
     */
    @Transactional(readOnly = true)
    public Set<String> capabilitiesOf(Long memberId) {
        if (memberId == null) {
            return Set.of();
        }

        List<Long> validRoleIds =
                memberRoleAssignmentRepository.findValidRoleIds(memberId, LocalDate.now(clock));
        if (validRoleIds.isEmpty()) {
            return Set.of();
        }

        List<String> grantedCodes =
                roleAuthorityRelationRepository.findAuthorityCodesByRoleIds(validRoleIds);
        if (grantedCodes.isEmpty()) {
            return Set.of();
        }

        return expandDownwards(grantedCodes);
    }

    /** 요구 권한을 가졌는지. capabilities와 같은 계산을 쓰므로 화면과 서버의 판정이 갈리지 않는다 */
    @Transactional(readOnly = true)
    public boolean hasAuthority(Long memberId, AuthorityCode required) {
        return capabilitiesOf(memberId).contains(required.code());
    }

    /** 세션·프로필 응답에 실을 형태. 정렬은 응답을 읽기 쉽게 하려는 것뿐이다 */
    @Transactional(readOnly = true)
    public List<String> capabilityListOf(Long memberId) {
        return capabilitiesOf(memberId).stream().sorted().toList();
    }

    /*
     * 직접 부여된 권한 목록을 자손까지 펼친 결과 (#65).
     *
     * capabilitiesOf가 '회원'에서 출발한다면 이쪽은 '이미 손에 든 권한 목록'에서 출발한다.
     * 역할별 권한 관리 화면이 "상위를 부여하면 자손도 부여된 것으로 보인다"를 그리려면 회원이
     * 아니라 역할의 부여 목록을 펼쳐야 하는데, 그 계산을 화면이나 다른 서비스가 다시 구현하면
     * 체크 상태와 실제 인가가 갈린다 (BR-M28과 같은 이유).
     */
    @Transactional(readOnly = true)
    public Set<String> expandOf(Collection<String> grantedCodes) {
        if (grantedCodes == null || grantedCodes.isEmpty()) {
            return Set.of();
        }
        return expandDownwards(grantedCodes);
    }

    /*
     * 부여받은 권한에서 자손까지 내려가며 펼친다.
     *
     * 방문 집합으로 같은 코드를 두 번 넣지 않으므로, 상위 지정 시의 순환 검사(AuthorityEntity)를
     * 어떤 이유로든 빠져나간 고리 데이터가 있어도 무한 루프에 빠지지 않는다 — 이 방어가 없으면
     * DB 한 행이 잘못 들어간 것만으로 인가 검사가 걸린 모든 요청이 멈춘다.
     */
    private Set<String> expandDownwards(Collection<String> grantedCodes) {
        Map<String, List<String>> childrenByParent = loadChildren();

        Set<String> expanded = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>();

        for (String granted : grantedCodes) {
            if (expanded.add(granted)) {
                pending.push(granted);
            }
        }

        while (!pending.isEmpty()) {
            String current = pending.pop();
            for (String child : childrenByParent.getOrDefault(current, List.of())) {
                if (expanded.add(child)) {
                    pending.push(child);
                }
            }
        }
        return expanded;
    }

    private Map<String, List<String>> loadChildren() {
        Map<String, List<String>> childrenByParent = new HashMap<>();
        for (AuthorityLink link : authorityRepository.findAllLinks()) {
            if (link.parentCode() == null) {
                continue;
            }
            childrenByParent
                    .computeIfAbsent(link.parentCode(), key -> new ArrayList<>())
                    .add(link.code());
        }
        return childrenByParent;
    }

    /*
     * "이 권한을 가진 회원은 누구인가"(#101) — capabilitiesOf·hasAuthority의 반대 방향이다.
     * 담당자 후보를 국장 이상으로만 좁히는 화면(GET /v1/members/assignable?authority=)이 쓴다.
     *
     * 펼침도 반대로 돈다. 회원 쪽은 위→아래(상위를 가지면 자손도 가진다)지만, 여기서는
     * required의 **조상**(부모→조부모→…→SUPER)을 전부 모아 그중 하나라도 직접 부여받은
     * 역할을 찾는다 — WORK_MANAGE를 직접 부여받은 역할뿐 아니라 그 상위 묶음(OPERATOR·
     * EXECUTIVE·SUPER)을 부여받은 역할도 결국 WORK_MANAGE를 행사할 수 있기 때문이다.
     */
    @Transactional(readOnly = true)
    public List<Long> memberIdsWithAuthority(AuthorityCode required) {
        Set<String> ancestorCodes = ancestorsOf(required.code());
        List<Long> roleIds =
                roleAuthorityRelationRepository.findRoleIdsByAuthorityCodes(ancestorCodes);
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return memberRoleAssignmentRepository.findMemberIdsByRoleIdsValidOn(
                roleIds, LocalDate.now(clock));
    }

    // code 자신을 포함해 up_authrt_cd를 따라 루트까지 올라간 코드 전부. 방문 집합이 고리 데이터를 막는다
    private Set<String> ancestorsOf(String code) {
        Map<String, String> parentByCode = loadParents();
        Set<String> ancestors = new HashSet<>();
        String current = code;
        while (current != null && ancestors.add(current)) {
            current = parentByCode.get(current);
        }
        return ancestors;
    }

    private Map<String, String> loadParents() {
        Map<String, String> parentByCode = new HashMap<>();
        for (AuthorityLink link : authorityRepository.findAllLinks()) {
            parentByCode.put(link.code(), link.parentCode());
        }
        return parentByCode;
    }
}
