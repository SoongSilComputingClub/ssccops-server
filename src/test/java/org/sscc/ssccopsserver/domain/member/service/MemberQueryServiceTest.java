package org.sscc.ssccopsserver.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.sscc.ssccopsserver.domain.member.code.MemberChangeType;
import org.sscc.ssccopsserver.domain.member.code.MemberGradeCode;
import org.sscc.ssccopsserver.domain.member.code.MemberStatusCode;
import org.sscc.ssccopsserver.domain.member.code.error.MemberErrorCode;
import org.sscc.ssccopsserver.domain.member.dto.AssignableMemberResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberChangeHistoryResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberDetailResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberSearchCondition;
import org.sscc.ssccopsserver.domain.member.dto.MemberSearchResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberSummaryResponse;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberGradeEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberGradeHistoryEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleAssignmentEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleClassificationEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberStatusEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberStatusHistoryEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeHistoryRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleClassificationRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusHistoryRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.global.apipayload.code.error.CommonErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;
import org.sscc.ssccopsserver.global.config.ClockConfig;
import org.sscc.ssccopsserver.global.config.JpaAuditingConfig;

/*
 * 회원 조회의 조회 규칙 자체를 다룬다 (#76) — 검색·필터·정렬·커서 페이징, 쿼리 횟수,
 * 현재 역할 판정, 최근 변경 이력의 합침.
 *
 * 인가 계단과 응답에 실리는 필드는 MemberQueryControllerTest가 맡는다. 클래스를 나눈 것은
 * 여기가 '여러 회원을 여러 등급·상태·이력으로 만들어 두는' 픽스처를 쓰기 때문이다
 * (WorkServiceImplSearchTest와 같은 이유).
 *
 * 현재 역할 판정이 주입된 Clock을 쓰는지 확인해야 하므로 Clock을 고정한다 — 시스템 시각을
 * 쓰고 있었다면 '아직 시작하지 않은 역할' 검증이 조용히 통과할 수 없다.
 *
 * @DataJpaTest가 @Configuration을 걸러내므로 JpaAuditingConfig를 명시적으로 들여온다.
 */
@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
// AuthorityPolicy는 @Service라 @DataJpaTest 슬라이스에 없다. MemberServiceImpl이 프로필의
// capabilities를 계산하는 데 쓰므로(#9) 정책과 그 Clock만 슬라이스에 들여온다.
@Import({JpaAuditingConfig.class, AuthorityPolicy.class, ClockConfig.class})
@ActiveProfiles("test")
class MemberQueryServiceTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 20);
    private static final Clock FIXED_CLOCK = Clock.fixed(TODAY.atStartOfDay(KST).toInstant(), KST);

    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberGradeHistoryRepository memberGradeHistoryRepository;
    @Autowired private MemberStatusHistoryRepository memberStatusHistoryRepository;
    @Autowired private AuthorityPolicy authorityPolicy;
    @Autowired private TestEntityManager entityManager;

    private MemberService memberService;

    @BeforeEach
    void setUp() {
        memberService =
                new MemberServiceImpl(
                        memberRepository,
                        memberRoleRepository,
                        memberRoleAssignmentRepository,
                        memberGradeRepository,
                        memberStatusRepository,
                        memberGradeHistoryRepository,
                        memberStatusHistoryRepository,
                        new MemberInitialHistoryRecorder(
                                memberGradeHistoryRepository, memberStatusHistoryRepository),
                        authorityPolicy,
                        FIXED_CLOCK);
    }

    /* ── 검색 ────────────────────────────────────────────── */

    // 이름과 학번 중 하나만 맞아도 걸린다
    @Test
    void keywordMatchesNameOrStudentNumber() {
        saveMember("20200001", "김도현", 31);
        saveMember("20210002", "이서연", 32);

        assertThat(namesOf(search(condition("도현")))).containsExactly("김도현");
        assertThat(namesOf(search(condition("2021")))).containsExactly("이서연");
        assertThat(namesOf(search(condition("연")))).containsExactly("이서연");
    }

    /*
     * 검색어의 와일드카드는 이스케이프된다. '_'를 그대로 흘려보내면 like에서 '아무 글자 하나'가
     * 되어 학번 하나만 찾으려던 검색이 명부 전체를 긁어 온다.
     */
    @Test
    void wildcardInKeywordIsEscaped() {
        saveMember("20200001", "김도현", 31);
        saveMember("20200002", "이서연", 32);

        assertThat(search(condition("2020_00"))).isEmpty();
        assertThat(search(condition("%"))).isEmpty();
    }

    // 검색어가 없으면 전체다. 공백만 있는 값도 검색어로 보지 않는다
    @Test
    void blankKeywordDoesNotFilter() {
        saveMember("20200001", "김도현", 31);
        saveMember("20200002", "이서연", 32);

        assertThat(search(condition(null))).hasSize(2);
        assertThat(search(condition("   "))).hasSize(2);
    }

    /* ── 필터 ────────────────────────────────────────────── */

    @Test
    void gradeFilterAcceptsMultipleCodes() {
        saveMember("20200001", "김도현", 31, MemberGradeCode.TEMP, MemberStatusCode.ENROLLED);
        saveMember("20200002", "이서연", 32, MemberGradeCode.ACTIVE, MemberStatusCode.ENROLLED);
        saveMember("20200003", "박준호", 33, MemberGradeCode.FULL, MemberStatusCode.ENROLLED);

        MemberSearchCondition condition =
                new MemberSearchCondition(null, List.of("TEMP", "FULL"), null, null, null, "mbrNm");

        assertThat(namesOf(search(condition))).containsExactly("김도현", "박준호");
    }

    @Test
    void statusFilterAcceptsMultipleCodes() {
        saveMember("20200001", "김도현", 31, MemberGradeCode.TEMP, MemberStatusCode.ENROLLED);
        saveMember("20200002", "이서연", 32, MemberGradeCode.TEMP, MemberStatusCode.GRADUATED);
        saveMember("20200003", "박준호", 33, MemberGradeCode.TEMP, MemberStatusCode.WITHDRAWN);

        MemberSearchCondition condition =
                new MemberSearchCondition(
                        null, null, List.of("GRADUATED", "WITHDRAWN"), null, null, "mbrNm");

        assertThat(namesOf(search(condition))).containsExactly("박준호", "이서연");
    }

    // 기준 코드 밖의 값은 조회가 아니라 조건 해석에서 걸린다
    @Test
    void filterOutsideCodeTableIsRejected() {
        MemberSearchCondition condition =
                new MemberSearchCondition(null, List.of("ALUMNI"), null, null, null, null);

        assertThatThrownBy(() -> memberService.searchMembers(condition))
                .isInstanceOf(GeneralException.class)
                .extracting(error -> ((GeneralException) error).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_CODE_VALUE);
    }

    /* ── 정렬 4종 ────────────────────────────────────────── */

    @Test
    void sortsByNameInBothDirections() {
        saveMember("20200001", "김도현", 31);
        saveMember("20200002", "박준호", 33);
        saveMember("20200003", "이서연", 32);

        assertThat(namesOf(search(sorted("mbrNm")))).containsExactly("김도현", "박준호", "이서연");
        assertThat(namesOf(search(sorted("-mbrNm")))).containsExactly("이서연", "박준호", "김도현");
    }

    /*
     * 기수는 숫자로 비교해야 한다. 커서에 실린 값을 문자열로 되돌려 비교하면 사전순이 되어
     * 10기가 2기보다 앞선다.
     */
    @Test
    void sortsByGenerationNumerically() {
        saveMember("20200001", "김도현", 2);
        saveMember("20200002", "이서연", 10);
        saveMember("20200003", "박준호", 31);

        assertThat(namesOf(search(sorted("genNo")))).containsExactly("김도현", "이서연", "박준호");
        assertThat(namesOf(search(sorted("-genNo")))).containsExactly("박준호", "이서연", "김도현");
    }

    @Test
    void sortsByJoinDate() {
        saveMember("20200001", "김도현", 31, LocalDate.of(2024, 3, 1));
        saveMember("20200002", "이서연", 32, LocalDate.of(2026, 3, 1));
        saveMember("20200003", "박준호", 33, LocalDate.of(2025, 3, 1));

        assertThat(namesOf(search(sorted("joinYmd")))).containsExactly("김도현", "박준호", "이서연");
        assertThat(namesOf(search(sorted("-joinYmd")))).containsExactly("이서연", "박준호", "김도현");
    }

    /*
     * 수정 시각은 감사 필드라 값을 손으로 정할 수 없다 — 저장 시각이 서로 밀리초 안에서
     * 겹치면 순서가 흔들리므로, 확인하려는 순서를 만들기 위해 crt_dt/mdfcn_dt를 직접 갈아 둔다.
     */
    @Test
    void sortsByUpdatedAt() {
        Long first = saveMember("20200001", "김도현", 31).getId();
        Long second = saveMember("20200002", "이서연", 32).getId();
        Long third = saveMember("20200003", "박준호", 33).getId();

        Instant base = TODAY.atStartOfDay(KST).toInstant();
        updateMemberModifiedAt(first, base);
        updateMemberModifiedAt(second, base.plusSeconds(60));
        updateMemberModifiedAt(third, base.plusSeconds(120));

        assertThat(namesOf(search(sorted("mdfcnDt")))).containsExactly("김도현", "이서연", "박준호");
        assertThat(namesOf(search(sorted("-mdfcnDt")))).containsExactly("박준호", "이서연", "김도현");
    }

    /* ── 커서 페이징 ─────────────────────────────────────── */

    /*
     * 페이지가 이어 붙어야 하고 겹치거나 빠지는 회원이 없어야 한다. 마지막 페이지에서는
     * 커서가 없고 hasNext가 false다.
     */
    @Test
    void cursorPagingWalksThroughEveryMemberExactlyOnce() {
        saveMember("20200001", "김도현", 31);
        saveMember("20200002", "박준호", 32);
        saveMember("20200003", "이서연", 33);
        saveMember("20200004", "정민수", 34);
        saveMember("20200005", "최유진", 35);

        MemberSearchResponse first =
                memberService.searchMembers(
                        new MemberSearchCondition(null, null, null, 2, null, "mbrNm"));
        assertThat(namesOf(first.members())).containsExactly("김도현", "박준호");
        assertThat(first.page().hasNext()).isTrue();
        assertThat(first.page().nextCursor()).isNotBlank();
        // 커서 페이징에도 전체 건수는 함께 내린다 — 화면이 '2건 · 전체 5건'을 그린다
        assertThat(first.page().totalCount()).isEqualTo(5);
        assertThat(first.page().overallCount()).isEqualTo(5);

        MemberSearchResponse second =
                memberService.searchMembers(
                        new MemberSearchCondition(
                                null, null, null, 2, first.page().nextCursor(), "mbrNm"));
        assertThat(namesOf(second.members())).containsExactly("이서연", "정민수");

        MemberSearchResponse third =
                memberService.searchMembers(
                        new MemberSearchCondition(
                                null, null, null, 2, second.page().nextCursor(), "mbrNm"));
        assertThat(namesOf(third.members())).containsExactly("최유진");
        assertThat(third.page().hasNext()).isFalse();
        assertThat(third.page().nextCursor()).isNull();
    }

    // 정렬 키가 같은 회원(동명이인)도 식별자로 끊어 한 번씩만 나온다
    @Test
    void cursorBreaksTiesByIdentifier() {
        saveMember("20200001", "김도현", 31);
        saveMember("20200002", "김도현", 32);
        saveMember("20200003", "김도현", 33);

        MemberSearchResponse first =
                memberService.searchMembers(
                        new MemberSearchCondition(null, null, null, 2, null, "mbrNm"));
        MemberSearchResponse second =
                memberService.searchMembers(
                        new MemberSearchCondition(
                                null, null, null, 2, first.page().nextCursor(), "mbrNm"));

        assertThat(idsOf(first.members())).hasSize(2);
        assertThat(idsOf(second.members())).hasSize(1);
        assertThat(idsOf(first.members())).doesNotContainAnyElementsOf(idsOf(second.members()));
    }

    // 다른 정렬로 받은 커서는 조용히 첫 페이지로 되돌리지 않는다
    @Test
    void cursorFromAnotherSortIsRejected() {
        saveMember("20200001", "김도현", 31);
        saveMember("20200002", "이서연", 32);

        String cursor =
                memberService
                        .searchMembers(
                                new MemberSearchCondition(null, null, null, 1, null, "mbrNm"))
                        .page()
                        .nextCursor();

        MemberSearchCondition mismatched =
                new MemberSearchCondition(null, null, null, 1, cursor, "genNo");

        assertThatThrownBy(() -> memberService.searchMembers(mismatched))
                .isInstanceOf(GeneralException.class)
                .extracting(error -> ((GeneralException) error).getErrorCode())
                .isEqualTo(MemberErrorCode.INVALID_CURSOR);
    }

    /* ── 쿼리 횟수 ───────────────────────────────────────── */

    /*
     * **회원이 몇 명이든 목록 조회의 쿼리 수는 변하지 않는다** (DB-13).
     *
     * 네 번이다 — 목록(등급·상태 조인) · 현재 역할 · 필터 건수 · 전체 건수. 등급·상태를
     * 지연 로딩으로 두거나 역할을 회원마다 조회했다면 회원 수에 비례해 늘어난다.
     *
     * 두 번 재어 같은지 보는 것이 요점이다 — 절대값만 못 박으면 N+1이 아니라 상수 하나가
     * 늘었을 때도 똑같이 실패해 무엇이 깨졌는지 알 수 없다.
     */
    @Test
    void listQueryCountDoesNotGrowWithMemberCount() {
        saveMember("20200001", "김도현", 31);
        saveMember("20200002", "이서연", 32);
        assignRole(memberRepository.findAll().get(0), "홍보국장", TODAY.minusYears(1), null, true);

        long withTwoMembers = countQueries(() -> search(condition(null)));

        for (int index = 3; index <= 8; index++) {
            MemberEntity member = saveMember("202000" + index, "회원" + index, 30 + index);
            assignRole(member, "국원" + index, TODAY.minusYears(1), null, false);
        }

        long withEightMembers = countQueries(() -> search(condition(null)));

        assertThat(withTwoMembers).isEqualTo(4);
        assertThat(withEightMembers).isEqualTo(withTwoMembers);
    }

    // 담당자 후보도 마찬가지다 — 후보 목록 1 + 현재 역할 1이며 후보 수와 무관하다
    @Test
    void assignableQueryCountDoesNotGrowWithMemberCount() {
        for (int index = 1; index <= 6; index++) {
            MemberEntity member = saveMember("202000" + index, "회원" + index, 30 + index);
            assignRole(member, "역할" + index, TODAY.minusYears(1), null, true);
        }

        assertThat(countQueries(memberService::findAssignableMembers)).isEqualTo(2);
    }

    /* ── 현재 역할 ───────────────────────────────────────── */

    /*
     * 현재 역할만 담는다 (BR-M25) — 임기가 끝난 역할과 아직 시작하지 않은 역할은 빠지고,
     * 종료일이 미래로 채워진 역할은 아직 유효하므로 남는다. 오늘은 주입된 Clock에서 온다.
     */
    @Test
    void detailContainsOnlyCurrentRoles() {
        MemberEntity member = saveMember("20200001", "김도현", 31);
        assignRole(member, "무기한국장", TODAY.minusYears(1), null, true);
        assignRole(member, "임기중국원", TODAY.minusMonths(1), TODAY.plusMonths(1), false);
        assignRole(member, "지난역할", TODAY.minusYears(2), TODAY.minusDays(1), false);
        assignRole(member, "미래역할", TODAY.plusDays(1), null, false);

        MemberDetailResponse detail = memberService.getMemberDetail(member.getId());

        assertThat(detail.roles())
                .extracting(role -> role.roleName())
                .containsExactlyInAnyOrder("무기한국장", "임기중국원");
    }

    // 목록에도 같은 규칙으로 현재 역할이 실린다
    @Test
    void listContainsCurrentRoles() {
        MemberEntity member = saveMember("20200001", "김도현", 31);
        assignRole(member, "홍보국장", TODAY.minusYears(1), null, true);
        assignRole(member, "지난역할", TODAY.minusYears(2), TODAY.minusDays(1), false);

        assertThat(search(condition(null)))
                .singleElement()
                .satisfies(
                        summary ->
                                assertThat(summary.roles())
                                        .extracting(role -> role.roleName())
                                        .containsExactly("홍보국장"));
    }

    /* ── 최근 변경 이력 ──────────────────────────────────── */

    /*
     * 등급 이력과 상태 이력을 **섞어** 기록 시각 역순으로 세 건만 담는다. 한쪽만 자르거나
     * 각각 세 건씩 그대로 내리면 이 검증이 깨진다.
     *
     * crt_dt는 감사 필드라 값을 손으로 정할 수 없어 저장 뒤에 직접 갈아 둔다 — 그러지 않으면
     * 네 행의 시각이 밀리초 안에서 겹쳐 순서가 흔들린다.
     */
    @Test
    void detailKeepsThreeMostRecentChangesMixingGradeAndStatus() {
        MemberEntity member = saveMember("20200001", "김도현", 31);
        MemberGradeEntity temp = grade(MemberGradeCode.TEMP);
        MemberGradeEntity active = grade(MemberGradeCode.ACTIVE);
        MemberStatusEntity enrolled = status(MemberStatusCode.ENROLLED);
        MemberStatusEntity onLeave = status(MemberStatusCode.LEAVE);

        Instant base = TODAY.atStartOfDay(KST).toInstant();
        Long oldestGrade =
                memberGradeHistoryRepository
                        .save(
                                MemberGradeHistoryEntity.create(
                                        member, null, temp, TODAY.minusYears(1), "회원가입", member))
                        .getId();
        Long firstStatus =
                memberStatusHistoryRepository
                        .save(
                                MemberStatusHistoryEntity.create(
                                        member,
                                        null,
                                        enrolled,
                                        TODAY.minusYears(1),
                                        null,
                                        "회원가입",
                                        member))
                        .getId();
        Long promotion =
                memberGradeHistoryRepository
                        .save(
                                MemberGradeHistoryEntity.create(
                                        member, temp, active, TODAY, "정회원 승급", member))
                        .getId();
        Long leave =
                memberStatusHistoryRepository
                        .save(
                                MemberStatusHistoryEntity.create(
                                        member, enrolled, onLeave, TODAY, null, "휴학", member))
                        .getId();

        updateHistoryCreatedAt("mbr_grd_hstry", "mbr_grd_hstry_id", oldestGrade, base);
        updateHistoryCreatedAt(
                "mbr_stts_hstry", "mbr_stts_hstry_id", firstStatus, base.plusSeconds(60));
        updateHistoryCreatedAt(
                "mbr_grd_hstry", "mbr_grd_hstry_id", promotion, base.plusSeconds(120));
        updateHistoryCreatedAt("mbr_stts_hstry", "mbr_stts_hstry_id", leave, base.plusSeconds(180));

        List<MemberChangeHistoryResponse> changes =
                memberService.getMemberDetail(member.getId()).recentChanges();

        assertThat(changes)
                .hasSize(3)
                .extracting(MemberChangeHistoryResponse::changeType)
                .containsExactly(
                        MemberChangeType.STATUS, MemberChangeType.GRADE, MemberChangeType.STATUS);
        assertThat(changes)
                .extracting(MemberChangeHistoryResponse::createdAt)
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());

        // 가장 오래된 한 건(가입 시 등급 부여)은 잘려 나갔다
        assertThat(changes)
                .extracting(MemberChangeHistoryResponse::changeReason)
                .containsExactly("휴학", "정회원 승급", "회원가입");

        // 이전 값이 비어 있는 최초 부여는 '신규'로 읽힌다
        assertThat(changes.get(2).previousCode()).isNull();
        assertThat(changes.get(1).previousCode()).isEqualTo(MemberGradeCode.TEMP.code());
        assertThat(changes.get(1).previousName()).isEqualTo("임시회원");
        assertThat(changes.get(0).changedByName()).isEqualTo("김도현");
    }

    /* ── 담당자 후보 ─────────────────────────────────────── */

    /*
     * 탈퇴·제명은 빠지고 휴학·졸업은 남는다 — 단건 판정(findAssignableMember)과 같은 규칙이라야
     * "목록에는 있는데 등록하면 거절되는 회원"이 생기지 않는다.
     */
    @Test
    void assignableUsesTheSameExclusionRuleAsTheSingleLookup() {
        saveMember("20200001", "재학회원", 31, MemberGradeCode.TEMP, MemberStatusCode.ENROLLED);
        saveMember("20200002", "휴학회원", 32, MemberGradeCode.TEMP, MemberStatusCode.MIL_LEAVE);
        saveMember("20200003", "졸업회원", 33, MemberGradeCode.TEMP, MemberStatusCode.GRADUATED);
        MemberEntity withdrawn =
                saveMember(
                        "20200004", "탈퇴회원", 34, MemberGradeCode.TEMP, MemberStatusCode.WITHDRAWN);
        MemberEntity expelled =
                saveMember("20200005", "제명회원", 35, MemberGradeCode.TEMP, MemberStatusCode.EXPELLED);

        /*
         * 순서는 이름 오름차순이다. 한글 정렬은 DB 콜레이션(H2·PostgreSQL 모두 코드 포인트)을
         * 따르므로 '재 < 졸 < 휴'가 된다 — 사전순 로케일 정렬을 기대하면 어긋난다.
         */
        assertThat(memberService.findAssignableMembers())
                .extracting(AssignableMemberResponse::name)
                .containsExactly("재학회원", "졸업회원", "휴학회원");

        // 걸러진 이유가 상태 조건임을 못 박는다 — mbr 행 자체는 남아 있다
        assertThat(memberRepository.findById(withdrawn.getId())).isPresent();
        assertThat(memberService.findAssignableMember(expelled.getId())).isEmpty();
    }

    /*
     * 대표 역할명은 rprs_role_yn = true인 현재 역할에서 온다. 대표로 지정된 역할이 없으면
     * null이며, 이 값은 표시용이라 정렬에는 쓰이지 않는다 (BR-M26).
     */
    @Test
    void assignableCarriesRepresentativeRoleName() {
        MemberEntity withRepresentative = saveMember("20200001", "김도현", 31);
        assignRole(withRepresentative, "부원", TODAY.minusYears(1), null, false);
        assignRole(withRepresentative, "홍보국장", TODAY.minusYears(1), null, true);

        MemberEntity withoutRepresentative = saveMember("20200002", "이서연", 32);
        assignRole(withoutRepresentative, "스터디장", TODAY.minusYears(1), null, false);

        saveMember("20200003", "박준호", 33);

        assertThat(memberService.findAssignableMembers())
                .extracting(
                        AssignableMemberResponse::name,
                        AssignableMemberResponse::representativeRoleName)
                .containsExactly(
                        // 정렬은 이름 오름차순이다. 대표 역할 유무가 순서를 바꾸지 않는다
                        org.assertj.core.api.Assertions.tuple("김도현", "홍보국장"),
                        org.assertj.core.api.Assertions.tuple("박준호", null),
                        org.assertj.core.api.Assertions.tuple("이서연", null));
    }

    /* ── 계정 연결 여부 ──────────────────────────────────── */

    /*
     * auth_user_id가 채워져 있는지를 linkedAccount로 내린다 (#85). CSV로 이관만 되고 아직
     * 로그인하지 않은 회원을 명부에서 가려내기 위한 값이며, UUID 자체는 내리지 않는다.
     */
    @Test
    void linkedAccountReflectsWhetherAuthUserIdIsFilled() {
        MemberEntity linked = saveMember("20200001", "김도현", 31);
        linked.assignAuthUserId(UUID.randomUUID());
        memberRepository.saveAndFlush(linked);
        saveMember("20200002", "이서연", 32);

        assertThat(search(sorted("mbrNm")))
                .extracting(MemberSummaryResponse::name, MemberSummaryResponse::linkedAccount)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("김도현", true),
                        org.assertj.core.api.Assertions.tuple("이서연", false));

        assertThat(memberService.getMemberDetail(linked.getId()).linkedAccount()).isTrue();
    }

    /* ── 기준 코드 ───────────────────────────────────────── */

    @Test
    void codeListsAreOrderedByDisplayOrder() {
        assertThat(memberService.findAllGrades())
                .extracting(response -> response.code())
                .containsExactly("TEMP", "ASSOC", "ACTIVE", "FULL");
        assertThat(memberService.findAllStatuses())
                .extracting(response -> response.code())
                .containsExactly(
                        "ENROLLED", "LEAVE", "MIL_LEAVE", "GRADUATED", "WITHDRAWN", "EXPELLED");
    }

    /* ── 없는 회원 ───────────────────────────────────────── */

    @Test
    void unknownMemberIsNotFound() {
        assertThatThrownBy(() -> memberService.getMemberDetail(999_999L))
                .isInstanceOf(GeneralException.class)
                .extracting(error -> ((GeneralException) error).getErrorCode())
                .isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);
    }

    /* ── 헬퍼 ────────────────────────────────────────────── */

    private List<MemberSummaryResponse> search(MemberSearchCondition condition) {
        return memberService.searchMembers(condition).members();
    }

    private static MemberSearchCondition condition(String keyword) {
        return new MemberSearchCondition(keyword, null, null, null, null, "mbrNm");
    }

    private static MemberSearchCondition sorted(String sort) {
        return new MemberSearchCondition(null, null, null, null, null, sort);
    }

    private static List<String> namesOf(List<MemberSummaryResponse> members) {
        return members.stream().map(MemberSummaryResponse::name).toList();
    }

    private static List<Long> idsOf(List<MemberSummaryResponse> members) {
        return members.stream().map(MemberSummaryResponse::memberId).toList();
    }

    private MemberEntity saveMember(String studentNumber, String name, int generationNumber) {
        return saveMember(
                studentNumber,
                name,
                generationNumber,
                MemberGradeCode.TEMP,
                MemberStatusCode.ENROLLED,
                TODAY.minusYears(1));
    }

    private MemberEntity saveMember(
            String studentNumber, String name, int generationNumber, LocalDate joinDate) {
        return saveMember(
                studentNumber,
                name,
                generationNumber,
                MemberGradeCode.TEMP,
                MemberStatusCode.ENROLLED,
                joinDate);
    }

    private MemberEntity saveMember(
            String studentNumber,
            String name,
            int generationNumber,
            MemberGradeCode gradeCode,
            MemberStatusCode statusCode) {
        return saveMember(
                studentNumber, name, generationNumber, gradeCode, statusCode, TODAY.minusYears(1));
    }

    private MemberEntity saveMember(
            String studentNumber,
            String name,
            int generationNumber,
            MemberGradeCode gradeCode,
            MemberStatusCode statusCode,
            LocalDate joinDate) {

        MemberEntity member =
                MemberEntity.create(
                        studentNumber,
                        generationNumber,
                        name,
                        "컴퓨터학부",
                        3,
                        "010-0000-0000",
                        studentNumber + "@sscc.org",
                        grade(gradeCode),
                        status(statusCode),
                        joinDate);
        return memberRepository.saveAndFlush(member);
    }

    private MemberGradeEntity grade(MemberGradeCode code) {
        return memberGradeRepository.findById(code.code()).orElseThrow();
    }

    private MemberStatusEntity status(MemberStatusCode code) {
        return memberStatusRepository.findById(code.code()).orElseThrow();
    }

    private void assignRole(MemberEntity member, String roleName, boolean representative) {
        assignRole(member, roleName, TODAY.minusYears(1), null, representative);
    }

    private void assignRole(
            MemberEntity member,
            String roleName,
            LocalDate startDate,
            LocalDate endDate,
            boolean representative) {

        MemberRoleClassificationEntity position =
                memberRoleClassificationRepository.findById("POSITION").orElseThrow();
        MemberRoleEntity role =
                memberRoleRepository.save(MemberRoleEntity.create(99, roleName, position));
        MemberRoleAssignmentEntity assignment =
                MemberRoleAssignmentEntity.create(member, role, startDate, representative);
        if (endDate != null) {
            assignment.end(endDate);
        }
        memberRoleAssignmentRepository.saveAndFlush(assignment);
    }

    /*
     * 감사 필드를 직접 갈아 끼운다. @LastModifiedDate·@CreatedDate가 저장 시각을 채우므로
     * 엔티티로는 정할 수 없고, 순서를 확인하려면 값이 서로 달라야 한다.
     *
     * 갈아 끼운 뒤 영속성 컨텍스트를 비워야 다음 조회가 1차 캐시의 낡은 값을 쓰지 않는다.
     */
    private void updateMemberModifiedAt(Long memberId, Instant modifiedAt) {
        entityManager
                .getEntityManager()
                .createNativeQuery("update mbr set mdfcn_dt = ?1 where mbr_id = ?2")
                .setParameter(1, Timestamp.from(modifiedAt))
                .setParameter(2, memberId)
                .executeUpdate();
        entityManager.clear();
    }

    private void updateHistoryCreatedAt(String table, String idColumn, Long id, Instant createdAt) {
        entityManager
                .getEntityManager()
                .createNativeQuery(
                        "update " + table + " set crt_dt = ?1 where " + idColumn + " = ?2")
                .setParameter(1, Timestamp.from(createdAt))
                .setParameter(2, id)
                .executeUpdate();
        entityManager.clear();
    }

    /*
     * 실행된 SQL 문 수. 픽스처가 남긴 지연 INSERT가 세어지지 않도록 먼저 flush·clear 하고
     * 통계를 초기화한다 (WorkServiceImplSearchTest와 같은 방식).
     */
    private long countQueries(Runnable action) {
        entityManager.flush();
        entityManager.clear();

        Statistics statistics =
                entityManager
                        .getEntityManager()
                        .getEntityManagerFactory()
                        .unwrap(SessionFactory.class)
                        .getStatistics();
        statistics.clear();

        action.run();

        return statistics.getPrepareStatementCount();
    }
}
