package org.sscc.ssccopsserver.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.entity.AuthorityEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.AuthorityRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleClassificationRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.domain.member.repository.RoleAuthorityRelationRepository;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;
import org.sscc.ssccopsserver.global.config.ClockConfig;
import org.sscc.ssccopsserver.support.AuthorityFixture;
import org.sscc.ssccopsserver.support.MemberFixture;

/*
 * 인가 판정의 규칙 자체를 확인한다 (#9 · ssccops#68의 수용 기준).
 *
 * 애스펙트 쪽 테스트(RequireAuthorityAspectTest)는 "거절이 어떤 응답이 되는가"를 보고,
 * 여기서는 "누가 무엇을 할 수 있는가"만 본다 — 규칙이 한 곳(AuthorityPolicy)에 있으므로
 * 판정 자체는 여기서 한 번만 검증한다.
 */
@DataJpaTest
@Import({AuthorityPolicy.class, ClockConfig.class})
@ActiveProfiles("test")
class AuthorityPolicyTest {

    @Autowired private AuthorityPolicy authorityPolicy;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private AuthorityRepository authorityRepository;
    @Autowired private RoleAuthorityRelationRepository roleAuthorityRelationRepository;

    /* ── 부여와 펼침 ─────────────────────────────────────── */

    @Test
    void directGrantSatisfiesTheRequirement() {
        MemberEntity member = saveMember("20260001", "직접부여");
        grant(member, AuthorityCode.WORK_MANAGE);

        assertThat(authorityPolicy.hasAuthority(member.getId(), AuthorityCode.WORK_MANAGE))
                .isTrue();
    }

    /*
     * 상위(묶음) 권한만 받아도 자손까지 펼쳐져 통과한다 (BR-M21).
     * EXECUTIVE 하나로 트리 전체에 닿는 것이 '임원'이라는 묶음의 뜻이다.
     */
    @Test
    void ancestorGrantExpandsDownToDescendants() {
        MemberEntity member = saveMember("20260002", "임원");
        grant(member, AuthorityCode.EXECUTIVE);

        assertThat(authorityPolicy.hasAuthority(member.getId(), AuthorityCode.FORM_READ)).isTrue();
        assertThat(authorityPolicy.hasAuthority(member.getId(), AuthorityCode.WORK_MANAGE))
                .isTrue();
        assertThat(authorityPolicy.hasAuthority(member.getId(), AuthorityCode.FORM_LABEL_MANAGE))
                .isTrue();
    }

    /** 두 단계 위(EXECUTIVE → OPERATOR → FORM_MANAGE → FORM_READ)도 같은 규칙으로 내려온다 */
    @Test
    void expansionCrossesEveryLevelOfTheTree() {
        MemberEntity member = saveMember("20260003", "운영자");
        grant(member, AuthorityCode.OPERATOR);

        assertThat(authorityPolicy.capabilitiesOf(member.getId()))
                .contains(
                        AuthorityCode.OPERATOR.code(),
                        AuthorityCode.FORM_MANAGE.code(),
                        AuthorityCode.FORM_STATUS_CHANGE.code());
    }

    /*
     * **펼침은 위→아래 한 방향이다** (BR-M22). 하위를 가졌다고 상위가 생기지 않는다 —
     * 이 규칙이 깨지면 '폼 조회'만 받은 사람이 '폼 관리' 전체를 갖게 된다.
     */
    @Test
    void descendantGrantDoesNotSatisfyAnAncestorRequirement() {
        MemberEntity member = saveMember("20260004", "폼조회자");
        grant(member, AuthorityCode.FORM_READ);

        assertThat(authorityPolicy.hasAuthority(member.getId(), AuthorityCode.FORM_READ)).isTrue();
        assertThat(authorityPolicy.hasAuthority(member.getId(), AuthorityCode.FORM_MANAGE))
                .isFalse();
        assertThat(authorityPolicy.hasAuthority(member.getId(), AuthorityCode.OPERATOR)).isFalse();
        assertThat(authorityPolicy.hasAuthority(member.getId(), AuthorityCode.EXECUTIVE)).isFalse();
    }

    /** 형제 권한으로도 새어 나가지 않는다 — FORM_READ가 FORM_WRITE를 주지 않는다 */
    @Test
    void siblingAuthoritiesStaySeparate() {
        MemberEntity member = saveMember("20260005", "형제확인");
        grant(member, AuthorityCode.FORM_READ);

        assertThat(authorityPolicy.hasAuthority(member.getId(), AuthorityCode.FORM_WRITE))
                .isFalse();
    }

    /* ── 역할이 없거나 권한이 없을 때 ─────────────────────── */

    @Test
    void memberWithoutAnyRoleHasNoCapability() {
        MemberEntity member = saveMember("20260006", "임시회원");

        assertThat(authorityPolicy.capabilitiesOf(member.getId())).isEmpty();
        assertThat(authorityPolicy.hasAuthority(member.getId(), AuthorityCode.WORK_MANAGE))
                .isFalse();
    }

    /** 권한이 하나도 매핑되지 않은 새 역할은 아무것도 못 한다 — 그것이 기본값이다 */
    @Test
    void roleWithoutAuthorityGrantsNothing() {
        MemberEntity member = saveMember("20260007", "권한없는역할");
        AuthorityFixture.grantRoleWithoutAuthority(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                member,
                "신설국장");

        assertThat(authorityPolicy.capabilitiesOf(member.getId())).isEmpty();
    }

    @Test
    void nullMemberIdHasNoCapability() {
        assertThat(authorityPolicy.capabilitiesOf(null)).isEmpty();
    }

    /* ── 유효 기간 ───────────────────────────────────────── */

    /*
     * 종료일이 지난 역할의 권한은 인정되지 않는다 (BR-M25). 배정 행은 남아 있으므로
     * "역할이 있다"만으로는 걸러지지 않는다는 것이 이 검사의 요점이다.
     */
    @Test
    void expiredRoleGrantsNothing() {
        MemberEntity member = saveMember("20260008", "임기만료");
        grantForPeriod(
                member,
                AuthorityCode.EXECUTIVE,
                LocalDate.now().minusYears(2),
                LocalDate.now().minusDays(1));

        assertThat(authorityPolicy.capabilitiesOf(member.getId())).isEmpty();
    }

    /** 시작일이 아직 오지 않은 역할도 마찬가지다 — 경계는 양쪽 모두 포함이다 */
    @Test
    void roleThatHasNotStartedYetGrantsNothing() {
        MemberEntity member = saveMember("20260009", "임기전");
        grantForPeriod(member, AuthorityCode.EXECUTIVE, LocalDate.now().plusDays(1), null);

        assertThat(authorityPolicy.capabilitiesOf(member.getId())).isEmpty();
    }

    @Test
    void roleEndingTodayIsStillValid() {
        MemberEntity member = saveMember("20260010", "오늘까지");
        grantForPeriod(
                member, AuthorityCode.WORK_MANAGE, LocalDate.now().minusDays(1), LocalDate.now());

        assertThat(authorityPolicy.hasAuthority(member.getId(), AuthorityCode.WORK_MANAGE))
                .isTrue();
    }

    /*
     * 여러 역할 중 하나만 유효해도 통과한다 (BR-M26 — 대표 역할 여부는 보지 않는다).
     * 만료된 역할이 더 넓은 권한을 갖고 있어도 그쪽은 더해지지 않는다.
     */
    @Test
    void anyOneValidRoleIsEnoughAndExpiredOnesAreIgnored() {
        MemberEntity member = saveMember("20260011", "역할두개");
        grantForPeriod(
                member,
                AuthorityCode.EXECUTIVE,
                LocalDate.now().minusYears(2),
                LocalDate.now().minusDays(1));
        grant(member, AuthorityCode.FORM_READ);

        assertThat(authorityPolicy.capabilitiesOf(member.getId()))
                .containsExactly(AuthorityCode.FORM_READ.code());
    }

    /* ── 순환 ────────────────────────────────────────────── */

    /** 자기 자신을 상위로 두는 것은 거절된다 (BR-M23) */
    @Test
    void authorityCannotBeItsOwnParent() {
        AuthorityEntity formManage =
                authorityRepository.findById(AuthorityCode.FORM_MANAGE.code()).orElseThrow();

        assertThatThrownBy(() -> formManage.changeParent(formManage))
                .isInstanceOf(GeneralException.class);
    }

    /** 자기 자손을 상위로 두는 것도 거절된다 — 조상 사슬을 거슬러 올라가 확인한다 */
    @Test
    void authorityCannotTakeItsOwnDescendantAsParent() {
        AuthorityEntity executive =
                authorityRepository.findById(AuthorityCode.EXECUTIVE.code()).orElseThrow();
        AuthorityEntity formRead =
                authorityRepository.findById(AuthorityCode.FORM_READ.code()).orElseThrow();

        assertThatThrownBy(() -> executive.changeParent(formRead))
                .isInstanceOf(GeneralException.class);
    }

    /*
     * 펼침은 고리가 있어도 멈춘다. 상위 지정 검사를 어떻게든 빠져나간 데이터가 남아 있어도
     * (직접 UPDATE 등) 인가 검사가 걸린 모든 요청이 멎어서는 안 된다.
     */
    @Test
    void expansionTerminatesEvenIfTheTreeHasACycle() {
        AuthorityEntity executive =
                authorityRepository.findById(AuthorityCode.EXECUTIVE.code()).orElseThrow();
        AuthorityEntity operator =
                authorityRepository.findById(AuthorityCode.OPERATOR.code()).orElseThrow();

        // 순환 검사를 우회해 EXECUTIVE ↔ OPERATOR 고리를 직접 만든다
        forceParent(executive, operator);
        authorityRepository.flush();

        MemberEntity member = saveMember("20260012", "고리");
        grant(member, AuthorityCode.EXECUTIVE);

        assertThat(authorityPolicy.capabilitiesOf(member.getId()))
                .contains(AuthorityCode.EXECUTIVE.code(), AuthorityCode.OPERATOR.code());
    }

    /* ── 헬퍼 ────────────────────────────────────────────── */

    private static void forceParent(AuthorityEntity child, AuthorityEntity parent) {
        try {
            java.lang.reflect.Field field = AuthorityEntity.class.getDeclaredField("parent");
            field.setAccessible(true);
            field.set(child, parent);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
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

    private void grant(MemberEntity member, AuthorityCode authority) {
        AuthorityFixture.grant(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                authorityRepository,
                roleAuthorityRelationRepository,
                member,
                authority);
    }

    private void grantForPeriod(
            MemberEntity member, AuthorityCode authority, LocalDate start, LocalDate end) {
        AuthorityFixture.grant(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                authorityRepository,
                roleAuthorityRelationRepository,
                member,
                authority,
                start,
                end);
    }
}
