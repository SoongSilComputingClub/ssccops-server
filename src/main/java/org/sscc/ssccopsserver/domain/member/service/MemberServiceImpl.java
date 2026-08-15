package org.sscc.ssccopsserver.domain.member.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.code.MemberGradeCode;
import org.sscc.ssccopsserver.domain.member.code.MemberStatusCode;
import org.sscc.ssccopsserver.domain.member.code.error.MemberErrorCode;
import org.sscc.ssccopsserver.domain.member.dto.AssignableMemberResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberChangeHistoryResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberCursor;
import org.sscc.ssccopsserver.domain.member.dto.MemberDetailResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberGradeResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberProfileResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberRoleResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberSearchCondition;
import org.sscc.ssccopsserver.domain.member.dto.MemberSearchQuery;
import org.sscc.ssccopsserver.domain.member.dto.MemberSearchResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberSignupRequest;
import org.sscc.ssccopsserver.domain.member.dto.MemberStatusResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberSummaryResponse;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberGradeEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberGradeHistoryEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleAssignmentEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberStatusEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberStatusHistoryEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeHistoryRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusHistoryRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.global.apipayload.PageResponse;
import org.sscc.ssccopsserver.global.apipayload.code.error.ErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;
import org.sscc.ssccopsserver.global.security.AuthenticatedUser;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MemberServiceImpl implements MemberService {

    /*
     * 담당자로 지정할 수 없는 회원 상태. 탈퇴·제명은 조직을 떠난 사람이라 새 업무를 맡길 수 없다 —
     * 이력 보존을 위해 mbr 행 자체는 남기므로 "회원이 있다"만으로는 걸러지지 않는다.
     * 휴학·졸업은 회원 자격이 유지되므로 뺄 이유가 없다 (졸업생 인수인계·감사 업무가 실제로 있다).
     */
    private static final List<String> UNASSIGNABLE_STATUS_CODES =
            List.of(MemberStatusCode.WITHDRAWN.code(), MemberStatusCode.EXPELLED.code());

    // 기수 미배정. 운영진이 사후에 배정하므로 가입 시점에는 0으로 둔다 (gen_no는 NOT NULL)
    private static final int UNASSIGNED_GENERATION_NUMBER = 0;

    /*
     * 최초 가입자에게 배정되는 역할의 이름 (#71 · ssccops#71). data.sql이 SYSTEM 분류로 넣고
     * SUPER 권한을 붙여 둔다.
     *
     * role_id가 아니라 이름을 상수로 두는 것은 role_id가 IDENTITY라 환경마다 다르기 때문이다 —
     * data.sql의 매핑 시드가 역할명으로 조회해 넣는 것과 같은 이유다.
     */
    private static final String BOOTSTRAP_ROLE_NAME = "최고관리자";

    // 최초 등급·상태 이력의 변경 사유. 이력만 봐도 운영진의 조정이 아니라 가입임을 알 수 있어야 한다
    private static final String SIGNUP_HISTORY_REASON = "회원가입";

    /*
     * 회원 상세에 싣는 최근 변경 이력의 건수 (#76). 상세 진입 한 번에 이력 전량을 실으면
     * 오래된 회원일수록 응답이 무한정 커진다 — 전체 이력은 별도 이슈(회원 변경 이력 통합 조회)다.
     */
    private static final int RECENT_CHANGE_LIMIT = 3;

    /*
     * 동시 가입 요청이 UNIQUE 제약에 걸렸을 때 어느 쪽인지 가리기 위한 제약명.
     * 드라이버가 예외 메시지에 제약명을 담아 주므로 그것으로 판별한다.
     */
    private static final String AUTH_USER_ID_CONSTRAINT = "uk_mbr_auth_user_id";
    private static final String STUDENT_NUMBER_CONSTRAINT = "uk_mbr_student_number";

    private final MemberRepository memberRepository;
    private final MemberRoleRepository memberRoleRepository;
    private final MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    private final MemberGradeRepository memberGradeRepository;
    private final MemberStatusRepository memberStatusRepository;
    private final MemberGradeHistoryRepository memberGradeHistoryRepository;
    private final MemberStatusHistoryRepository memberStatusHistoryRepository;

    // 프로필의 capabilities는 인가 애스펙트와 같은 정책으로 계산한다 (#9) — 두 벌로 두면 갈린다
    private final AuthorityPolicy authorityPolicy;

    // 가입일 산출 기준 시각. 테스트에서 고정할 수 있도록 주입받는다 (ClockConfig)
    private final Clock clock;

    /*
     * 회원 생성과 가입 이력이 한 트랜잭션이다. 회원만 남고 이력이 없으면 회원 상세의 변경이력이
     * 최초 부여 시점을 영영 보여줄 수 없어 경계를 쪼개지 않는다.
     */
    @Override
    @Transactional
    public MemberProfileResponse signUp(AuthenticatedUser user, MemberSignupRequest request) {
        UUID authUserId = user.authUserId();
        if (memberRepository.existsByAuthUserId(authUserId)) {
            throw new GeneralException(MemberErrorCode.ALREADY_SIGNED_UP);
        }

        MemberStatusCode statusCode = request.memberStatusCode();
        // 요청 DTO가 이미 걸러내지만, Service를 직접 호출하는 경로에서도 성립해야 하는 규칙이다
        if (!statusCode.isSignupSelectable()) {
            throw new GeneralException(MemberErrorCode.SIGNUP_STATUS_NOT_ALLOWED);
        }

        /*
         * 학번 미입력은 빈 문자열이 아니라 NULL로 저장한다 — uk_mbr_student_number가 살아 있어
         * 빈 문자열로 채우면 두 번째 졸업 회원부터 UNIQUE 충돌이 난다.
         */
        String studentNumber = trimToNull(request.studentNumber());
        if (studentNumber != null && memberRepository.existsByStudentNumber(studentNumber)) {
            throw new GeneralException(MemberErrorCode.STUDENT_NUMBER_DUPLICATED);
        }

        // 가입 등급은 항상 임시회원이다. 승급은 운영진의 별도 절차이지 가입자가 정할 값이 아니다
        MemberGradeEntity grade = findGrade(MemberGradeCode.TEMP);
        MemberStatusEntity status = findStatus(statusCode);

        /*
         * 부트스트랩 판정은 회원을 만들기 **전에** 한다 — 만든 뒤에 세면 방금 넣은 본인이
         * 세어져 언제나 1이라 아무도 최초 가입자가 되지 못한다.
         */
        MemberRoleEntity bootstrapRole = claimBootstrapRole();

        MemberEntity member =
                MemberEntity.create(
                        studentNumber,
                        request.generationNumber() == null
                                ? UNASSIGNED_GENERATION_NUMBER
                                : request.generationNumber(),
                        request.name().trim(),
                        trimToNull(request.departmentName()),
                        request.academicYear(),
                        request.phoneNumber().trim(),
                        // 이메일은 요청이 아니라 소셜 계정에서 온다 (화면도 읽기 전용으로 표시한다)
                        user.email(),
                        grade,
                        status,
                        LocalDate.now(clock));
        member.assignAuthUserId(authUserId);

        MemberEntity saved = saveOrTranslateConflict(member);
        recordInitialHistories(saved, grade, status);

        if (bootstrapRole == null) {
            /*
             * 평상시 가입에는 어떤 역할도 부여되지 않는다 — 역할 배정은 운영진의 별도 절차다.
             * 역할이 없으면 권한도 없으므로 capabilities도 빈 목록이다(굳이 조회하지 않는다).
             */
            return MemberProfileResponse.of(saved, List.of(), List.of());
        }
        return grantBootstrapRole(saved, bootstrapRole);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MemberEntity> findByAuthUserId(UUID authUserId) {
        if (authUserId == null) {
            return Optional.empty();
        }
        return memberRepository.findByAuthUserId(authUserId);
    }

    @Override
    @Transactional(readOnly = true)
    public MemberProfileResponse getProfile(Long memberId) {
        MemberEntity member =
                memberRepository
                        .findById(memberId)
                        .orElseThrow(() -> new GeneralException(MemberErrorCode.MEMBER_NOT_FOUND));

        return MemberProfileResponse.of(
                member, findCurrentRoles(memberId), authorityPolicy.capabilityListOf(memberId));
    }

    /*
     * 현재 역할만 고른다 — 종료일이 채워진 배정은 지난 역할이라 권한 판정에도 화면에도 쓰이지 않는다.
     * 회원이 실재하는지는 확인하지 않는다. 역할이 없는 것과 회원이 없는 것 모두 빈 목록이며,
     * 이 메서드를 부르는 쪽(프로필 조회·승인 권한 판정)은 이미 회원을 손에 쥐고 있다.
     */
    @Override
    @Transactional(readOnly = true)
    public List<MemberRoleResponse> findCurrentRoles(Long memberId) {
        if (memberId == null) {
            return List.of();
        }
        return memberRoleAssignmentRepository.findCurrentByMemberId(memberId).stream()
                .map(MemberRoleResponse::from)
                .toList();
    }

    /*
     * 회원 실재 여부에 더해 배정 가능한 상태인지까지 본다 (UNASSIGNABLE_STATUS_CODES 참고).
     * 걸러진 회원은 빈 Optional로 돌아가고, 호출부(업무·하위 업무 등록)는 이를 "없는 담당자"와
     * 똑같이 다룬다 — 탈퇴 사실이 오류 메시지로 새어 나가지 않게 하려는 것이기도 하다.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<MemberEntity> findAssignableMember(Long memberId) {
        if (memberId == null) {
            return Optional.empty();
        }
        return memberRepository.findAssignableById(memberId, UNASSIGNABLE_STATUS_CODES);
    }

    /*
     * 회원 관리 목록 (#76). 화면의 표 한 장이 이 호출 하나다.
     *
     * 쿼리는 네 번이다 — 목록 · 현재 역할 · 필터 건수 · 전체 건수. **회원이 몇 명이든 이 수는
     * 변하지 않는다** (DB-13). 등급·상태는 목록 쿼리가 조인으로 함께 끌어오고, 회원마다 여러
     * 건인 현재 역할만 이번 페이지의 식별자로 한 번에 모아 온다. 목록이 비면 역할 쿼리는
     * 아예 부르지 않는다 — 빈 컬렉션을 IN에 넘기면 DB에 따라 문법 오류다.
     */
    @Override
    @Transactional(readOnly = true)
    public MemberSearchResponse searchMembers(MemberSearchCondition condition) {
        MemberSearchQuery query = condition.toQuery();

        // 다음 페이지가 있는지 알기 위해 한 건 더 읽어 왔으므로, 남는 한 건은 응답에서 덜어낸다
        List<MemberEntity> fetched = memberRepository.search(query);
        boolean hasNext = fetched.size() > query.size();
        List<MemberEntity> rows = hasNext ? fetched.subList(0, query.size()) : fetched;

        Map<Long, List<MemberRoleResponse>> rolesByMemberId = currentRolesOf(rows);
        List<MemberSummaryResponse> members =
                rows.stream()
                        .map(
                                member ->
                                        MemberSummaryResponse.of(
                                                member,
                                                rolesByMemberId.getOrDefault(
                                                        member.getId(), List.of())))
                        .toList();

        PageResponse page =
                new PageResponse(
                        query.size(),
                        query.sort().getParameter(),
                        nextCursorOf(query, rows, hasNext),
                        hasNext,
                        memberRepository.countMatching(query),
                        memberRepository.count());
        return new MemberSearchResponse(members, page);
    }

    /*
     * 회원 단건 (#76). 쿼리는 네 번이다 — 회원(등급·상태 포함) · 현재 역할 · 등급 이력 ·
     * 상태 이력.
     *
     * 이력 두 벌을 각각 세 건씩만 읽어 합친 뒤 다시 세 건으로 자른다. 한쪽이 최근 세 건을
     * 독차지할 수 있으므로 양쪽에서 세 건씩 읽어야 합친 결과의 상위 세 건이 옳다.
     */
    @Override
    @Transactional(readOnly = true)
    public MemberDetailResponse getMemberDetail(Long memberId) {
        MemberEntity member =
                memberRepository
                        .findWithGradeAndStatusById(memberId)
                        .orElseThrow(() -> new GeneralException(MemberErrorCode.MEMBER_NOT_FOUND));

        return MemberDetailResponse.of(
                member,
                currentRolesOf(List.of(member)).getOrDefault(memberId, List.of()),
                recentChangesOf(memberId));
    }

    /*
     * 담당자 후보 목록 (#76). 대상 판정은 단건판(findAssignableMember)과 같은
     * UNASSIGNABLE_STATUS_CODES를 쓴다 — 규칙이 두 벌이 되면 목록과 등록이 갈린다.
     *
     * 쿼리는 두 번이다(후보 목록 · 현재 역할). 대표 역할명을 채우려면 역할이 필요한데,
     * 후보마다 조회하면 그대로 N+1이라 목록과 같은 방식으로 한 번에 모아 온다.
     */
    @Override
    @Transactional(readOnly = true)
    public List<AssignableMemberResponse> findAssignableMembers() {
        List<MemberEntity> candidates =
                memberRepository.findAllAssignable(UNASSIGNABLE_STATUS_CODES);
        Map<Long, List<MemberRoleResponse>> rolesByMemberId = currentRolesOf(candidates);

        return candidates.stream()
                .map(
                        member ->
                                AssignableMemberResponse.of(
                                        member,
                                        representativeRoleNameOf(
                                                rolesByMemberId.getOrDefault(
                                                        member.getId(), List.of()))))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<MemberGradeResponse> findAllGrades() {
        return memberGradeRepository.findAllByOrderByDisplayOrderAscCodeAsc().stream()
                .map(MemberGradeResponse::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<MemberStatusResponse> findAllStatuses() {
        return memberStatusRepository.findAllByOrderByDisplayOrderAscCodeAsc().stream()
                .map(MemberStatusResponse::from)
                .toList();
    }

    // 다음 커서는 이번 페이지의 마지막 행을 가리킨다. 마지막 페이지면 커서가 없다
    private String nextCursorOf(MemberSearchQuery query, List<MemberEntity> rows, boolean hasNext) {
        return hasNext ? MemberCursor.of(query.sort(), rows.get(rows.size() - 1)).encode() : null;
    }

    /*
     * 여러 회원의 현재 역할을 한 번에 모아 회원별로 나눈다 (#76).
     *
     * 판정 규칙은 BR-M25다 — role_bgng_ymd <= 오늘 <= role_end_ymd이며 종료일이 NULL이면
     * 무기한이고, 오늘은 주입된 Clock에서 온다. 인가 판정(AuthorityPolicy)이 보는 '유효한
     * 역할'과 같은 기준이라 화면의 역할 배지와 실제 권한이 갈리지 않는다.
     *
     * 대표 역할 여부(rprs_role_yn)로 걸러내거나 정렬하지 않는다 (BR-M26). 표시용 값이라
     * 응답에 실릴 뿐이며, 순서는 역할의 표시 순번이 정한다.
     */
    private Map<Long, List<MemberRoleResponse>> currentRolesOf(List<MemberEntity> members) {
        if (members.isEmpty()) {
            // IN () 은 DB에 따라 문법 오류이므로 애초에 쿼리를 보내지 않는다
            return Map.of();
        }
        List<Long> memberIds = members.stream().map(MemberEntity::getId).toList();
        return memberRoleAssignmentRepository
                .findValidByMemberIds(memberIds, LocalDate.now(clock))
                .stream()
                .collect(
                        Collectors.groupingBy(
                                assignment -> assignment.getMember().getId(),
                                LinkedHashMap::new,
                                Collectors.mapping(MemberRoleResponse::from, Collectors.toList())));
    }

    /*
     * 등급 이력과 상태 이력을 합쳐 기록 시각 역순으로 자른다 (#76).
     *
     * 같은 시각의 두 이력(한 트랜잭션에서 등급과 상태를 함께 바꾼 경우)은 순서를 못 박을
     * 근거가 없으므로 종류로 끊는다 — 근거 없는 흔들림보다 임의라도 고정된 순서가 낫다.
     */
    private List<MemberChangeHistoryResponse> recentChangesOf(Long memberId) {
        Pageable limit = PageRequest.of(0, RECENT_CHANGE_LIMIT);

        Stream<MemberChangeHistoryResponse> gradeChanges =
                memberGradeHistoryRepository
                        .findByMemberIdOrderByCreatedAtDescIdDesc(memberId, limit)
                        .stream()
                        .map(MemberChangeHistoryResponse::from);
        Stream<MemberChangeHistoryResponse> statusChanges =
                memberStatusHistoryRepository
                        .findByMemberIdOrderByCreatedAtDescIdDesc(memberId, limit)
                        .stream()
                        .map(MemberChangeHistoryResponse::from);

        return Stream.concat(gradeChanges, statusChanges)
                .sorted(
                        Comparator.comparing(
                                        MemberChangeHistoryResponse::createdAt,
                                        Comparator.reverseOrder())
                                .thenComparing(MemberChangeHistoryResponse::changeType))
                .limit(RECENT_CHANGE_LIMIT)
                .toList();
    }

    /*
     * 대표 역할의 이름. 대표로 지정된 역할이 없으면 null이며 화면은 그 자리를 비워 둔다.
     * 여러 역할이 대표로 지정돼 있는 데이터를 만나면 목록 순서(역할 표시 순번)의 첫 번째를
     * 고른다 — 대표는 하나뿐이어야 하지만 DB가 그것을 보장하지는 않는다.
     */
    private static String representativeRoleNameOf(List<MemberRoleResponse> roles) {
        return roles.stream()
                .filter(MemberRoleResponse::representative)
                .map(MemberRoleResponse::roleName)
                .findFirst()
                .orElse(null);
    }

    /*
     * 최초 가입자 부트스트랩 창구가 열려 있으면 배정할 역할을, 닫혀 있으면 null을 돌려준다 (#71).
     *
     * ── 왜 '회원이 한 명도 없는가'인가 ─────────────────────────────
     * 'SUPER를 가진 사람이 없는가'가 아니다(BR-M37). 그 기준이면 최고관리자가 탈퇴할 때마다
     * 창구가 다시 열려, 가입 순서만으로 시스템 전체를 가져가는 길이 생긴다. 빈 회원 테이블은
     * 한 번뿐이고 되돌아오지 않는 사실이라 창구로 쓸 수 있다.
     *
     * ── 왜 두 번 세는가 ────────────────────────────────────────
     * 1차 검사는 잠금 없이 한다. 평상시 가입은 여기서 끝나므로 회원이 한 명이라도 있으면
     * 비관적 잠금이 아예 걸리지 않는다. 창구가 열려 있어 보일 때만 역할 행을 잠그고
     * **그 뒤에** 다시 센다 — 순서를 뒤집으면 잠금을 기다리는 사이 앞선 트랜잭션이 커밋해
     * 버려 이미 낡은 숫자를 손에 쥔 채 통과한다(VR-M14). 잠금은 트랜잭션이 끝날 때까지
     * 유지되므로 회원 INSERT까지가 한 줄로 직렬화된다.
     */
    private MemberRoleEntity claimBootstrapRole() {
        if (memberRepository.count() > 0) {
            return null;
        }

        MemberRoleEntity role =
                memberRoleRepository.findAllByNameForUpdate(BOOTSTRAP_ROLE_NAME).stream()
                        .findFirst()
                        .orElseThrow(() -> missingSeed("부트스트랩 역할", BOOTSTRAP_ROLE_NAME));

        return memberRepository.count() == 0 ? role : null;
    }

    /*
     * 최초 가입자에게 부트스트랩 역할을 배정하고, 그로써 열린 권한을 응답에 실어 돌려준다.
     *
     * 회원에게 권한을 직접 붙이지 않고 역할을 배정한다(BR-M38). 회원↔권한 관계를 따로 만들면
     * 인가 판정 경로가 두 벌이 되고, AuthorityPolicy가 보는 '회원 → 역할 → 권한' 한 줄이
     * 더는 전부가 아니게 된다(BR-M28).
     *
     * ── 응답에 역할과 capabilities를 싣는 이유 ─────────────────────
     * 평상시 가입은 둘 다 비어 있는 것이 맞다(역할이 없으니까). 그런데 최초 가입자에게도 빈
     * 목록을 주면 화면은 방금 최고관리자가 된 사람을 권한 없는 회원으로 그린다 — 새로고침해야
     * 메뉴가 나타나는 상태가 된다.
     *
     * ── 부여 결과를 되물어 확인한다 ────────────────────────────────
     * capabilities에 SUPER가 없다면 시드가 깨진 것이다(권한 행이 없거나 역할↔권한 매핑이
     * 지워졌거나). 그대로 통과시키면 권한 없는 첫 회원이 남아 mbr이 더는 비어 있지 않게 되고
     * **부트스트랩 창구가 영영 닫힌다** — 복구는 다시 수동 SQL이다(VR-M15). 예외로 끊어
     * 트랜잭션째 되돌린다. 사용자 입력 문제가 아니므로 400이 아니라 500이다.
     *
     * 확인에 인가 애스펙트와 같은 AuthorityPolicy를 쓰는 것이 중요하다. 매핑 행의 존재만 따로
     * 확인하면 "응답에는 권한이 보이는데 실제 요청은 403"이 될 자리가 생긴다.
     */
    private MemberProfileResponse grantBootstrapRole(MemberEntity member, MemberRoleEntity role) {
        MemberRoleAssignmentEntity assignment =
                memberRoleAssignmentRepository.saveAndFlush(
                        MemberRoleAssignmentEntity.create(
                                member, role, member.getJoinDate(), true));

        List<String> capabilities = authorityPolicy.capabilityListOf(member.getId());
        if (!capabilities.contains(AuthorityCode.SUPER.code())) {
            throw missingSeed("최고 관리자 권한", AuthorityCode.SUPER.code());
        }

        return MemberProfileResponse.of(
                member, List.of(MemberRoleResponse.from(assignment)), capabilities);
    }

    /*
     * 선조회만으로는 같은 계정·같은 학번의 동시 요청을 막지 못한다 — 두 요청이 나란히 조회를
     * 통과한 뒤 둘 다 INSERT 하면 한쪽이 UNIQUE 제약에 걸린다. 그 경우도 선조회와 같은 409로
     * 내려야 프론트가 두 경로를 다르게 다루지 않아도 된다.
     *
     * 제약 위반은 flush 시점에야 드러나므로 saveAndFlush로 이 메서드 안에서 잡는다.
     */
    /*
     * 등급·상태의 최초 부여도 이력에 남긴다. 이후 변경만 기록하면 회원 상세의 변경이력이
     * "언제 무엇으로 시작했는지"를 보여줄 수 없고, 첫 승급 이력의 이전 등급이 근거 없이 떠 있게 된다.
     *
     * 이전 값은 NULL이다 — 가입 전에는 등급도 상태도 없었다는 사실 그대로다.
     * 변경자는 본인이다. 운영진이 개입한 변경이 아니라 본인 신청으로 생긴 값이기 때문이다.
     */
    private void recordInitialHistories(
            MemberEntity member, MemberGradeEntity grade, MemberStatusEntity status) {
        LocalDate appliedDate = member.getJoinDate();

        memberGradeHistoryRepository.save(
                MemberGradeHistoryEntity.create(
                        member, null, grade, appliedDate, SIGNUP_HISTORY_REASON, member));
        memberStatusHistoryRepository.save(
                MemberStatusHistoryEntity.create(
                        member, null, status, appliedDate, null, SIGNUP_HISTORY_REASON, member));
    }

    private MemberEntity saveOrTranslateConflict(MemberEntity member) {
        try {
            return memberRepository.saveAndFlush(member);
        } catch (DataIntegrityViolationException ex) {
            throw new GeneralException(resolveConflictErrorCode(ex));
        }
    }

    private static ErrorCode resolveConflictErrorCode(DataIntegrityViolationException ex) {
        Throwable cause = ex.getMostSpecificCause();
        String message =
                cause.getMessage() == null ? "" : cause.getMessage().toLowerCase(Locale.ROOT);

        if (message.contains(STUDENT_NUMBER_CONSTRAINT)) {
            return MemberErrorCode.STUDENT_NUMBER_DUPLICATED;
        }
        /*
         * 제약명을 읽지 못하는 드라이버도 있어 기본값이 필요하다. 같은 계정의 중복 제출(더블 클릭)이
         * 서로 다른 계정이 같은 학번을 동시에 내는 경우보다 훨씬 흔하므로 재가입 쪽으로 안내한다.
         */
        return MemberErrorCode.ALREADY_SIGNED_UP;
    }

    private MemberGradeEntity findGrade(MemberGradeCode gradeCode) {
        return memberGradeRepository
                .findById(gradeCode.code())
                .orElseThrow(() -> missingSeed("회원 등급", gradeCode.code()));
    }

    private MemberStatusEntity findStatus(MemberStatusCode statusCode) {
        return memberStatusRepository
                .findById(statusCode.code())
                .orElseThrow(() -> missingSeed("회원 상태", statusCode.code()));
    }

    /*
     * 기준 코드·기준 데이터는 data.sql이 매 기동마다 시드하므로 없을 수 없다. 없다면 사용자
     * 입력 문제가 아니라 시드가 깨진 것이라 400이 아니라 500으로 드러나야 한다.
     */
    private static IllegalStateException missingSeed(String seedName, String code) {
        return new IllegalStateException("%s 시드가 없습니다: %s".formatted(seedName, code));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
