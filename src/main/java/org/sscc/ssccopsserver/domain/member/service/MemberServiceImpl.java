package org.sscc.ssccopsserver.domain.member.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
import org.sscc.ssccopsserver.domain.member.dto.MemberLinkRequest;
import org.sscc.ssccopsserver.domain.member.dto.MemberProfileResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberRoleResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberSearchCondition;
import org.sscc.ssccopsserver.domain.member.dto.MemberSearchQuery;
import org.sscc.ssccopsserver.domain.member.dto.MemberSearchResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberSelfUpdateRequest;
import org.sscc.ssccopsserver.domain.member.dto.MemberSignupRequest;
import org.sscc.ssccopsserver.domain.member.dto.MemberStatusResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberSummaryResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberUpdateRequest;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberGradeEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleAssignmentEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberStatusEntity;
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
import lombok.extern.slf4j.Slf4j;

@Slf4j
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

    /*
     * 등급·상태의 최초 이력 기록 (BR-M47). 가입과 CSV 이관(#85)이 **같은 한 벌**을 쓴다 —
     * 복제하면 한쪽만 이력을 남기거나 bfr_*의 뜻이 갈린다.
     */
    private final MemberInitialHistoryRecorder initialHistoryRecorder;

    // 프로필의 capabilities는 인가 애스펙트와 같은 정책으로 계산한다 (#9) — 두 벌로 두면 갈린다
    private final AuthorityPolicy authorityPolicy;

    /*
     * 계정 연결의 시도 횟수 제한 (#86 · VR-M24). 제한 단위와 잠금 시간, 인메모리 구현의 한계는
     * MemberLinkAttemptLimiter의 주석에 있다.
     */
    private final MemberLinkAttemptLimiter linkAttemptLimiter;

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

    /*
     * 이관 회원 계정 연결 (#86 · ssccops#78 A안).
     *
     * ── 순서가 규칙이다 ────────────────────────────────────────
     * 1) 이미 가입한 계정인가 — 연결은 가입 전 주체의 일이다.
     * 2) 시도 제한에 걸렸는가 — **명부를 보기 전에** 끊어야 제한이 뜻을 갖는다. 조회한 뒤에
     *    응답만 막으면 타이밍으로 존재 여부가 새어 나가고, 애초에 훑는 일 자체를 막지 못한다.
     * 3) 후보 조회 → 본인 확인 → assignAuthUserId.
     *
     * ── mbr 행을 만들지 않는다 (BR-M51) ────────────────────────
     * 여기에는 MemberEntity.create도 이력 기록도 없다. 등급·상태는 명부에 이미 있고 그 최초
     * 부여 이력은 이관(#85)이 남겼으므로, 연결이 또 남기면 "이관됐다"와 "본인이 로그인했다"가
     * 같은 종류의 사건으로 섞인다. 연결 사실은 **로그로만** 남긴다 — 데이터사전에 연결 이력
     * 테이블이 없고, mbr_stts_hstry에 상태 변화 없는 행을 억지로 넣으면 이력의 뜻이 흐려진다.
     *
     * ── 트랜잭션 ───────────────────────────────────────────────
     * 갱신은 auth_user_id 한 컬럼뿐이지만 flush를 명시적으로 부른다. uk_mbr_auth_user_id 위반은
     * flush 시점에야 드러나고, 커밋까지 미루면 도메인 오류(409)가 아니라 500으로 새어 나간다.
     */
    @Override
    @Transactional
    public MemberProfileResponse link(AuthenticatedUser user, MemberLinkRequest request) {
        UUID authUserId = user.authUserId();
        if (memberRepository.existsByAuthUserId(authUserId)) {
            throw new GeneralException(MemberErrorCode.ALREADY_SIGNED_UP);
        }
        if (linkAttemptLimiter.isLocked(authUserId)) {
            throw new GeneralException(MemberErrorCode.TOO_MANY_LINK_ATTEMPTS);
        }

        MemberEntity member = findLinkTarget(request, authUserId);
        member.assignAuthUserId(authUserId);
        flushOrTranslateLinkConflict();

        linkAttemptLimiter.reset(authUserId);
        /*
         * 연결 사실이 남는 유일한 자리다. 누가 어느 명부 행을 가져갔는지는 나중에 반드시 묻게
         * 되는 질문이라, 화면에 필요해지면 테이블 등재를 별도 TASK로 세운다.
         */
        log.info("이관 회원 계정 연결 완료: mbrId={}, authUserId={}", member.getId(), authUserId);

        Long memberId = member.getId();
        return MemberProfileResponse.of(
                member, findCurrentRoles(memberId), authorityPolicy.capabilityListOf(memberId));
    }

    /*
     * 연결할 명부 회원을 찾는다. 실패는 **한 코드 한 문구**다 (VR-M23).
     *
     * 후보는 auth_user_id가 비어 있는 회원으로 좁혀 조회하고(이미 계정이 붙은 행은 연결 대상이
     * 아니다), 학번·회원명·연락처 3종 일치를 MemberLinkPolicy가 판정한다. **정확히 한 건**일
     * 때만 연결하며, 0건이든 2건 이상이든 같은 실패다 — uk_mbr_student_number 덕분에 2건은
     * 나올 수 없지만, 규칙을 제약에 기대지 않고 여기서 센다.
     *
     * 3종이 다 맞았는데 그 행에 이미 계정이 붙어 있는 경우만 409로 갈라낸다. 여기 닿은 사람은
     * 연락처까지 맞힌 사람이라 이 응답이 새로 알려 주는 사실이 없고(연락처는 MEMBER_MANAGE
     * 없이는 조회되지 않는다), 화면은 "정보가 틀렸다"가 아니라 "운영진에게 문의하라"고 안내해야
     * 한다. 같은 이유로 **이 경우는 실패 횟수에 세지 않는다** — 추측이 아니기 때문이다.
     */
    private MemberEntity findLinkTarget(MemberLinkRequest request, UUID authUserId) {
        String studentNumber = MemberLinkPolicy.normalizeStudentNumber(request.stdntNo());

        List<MemberEntity> matched =
                matching(
                        memberRepository.findLinkCandidatesByStudentNumber(studentNumber), request);
        if (matched.size() == 1) {
            return lockForLink(matched.get(0));
        }

        if (!matching(memberRepository.findLinkedByStudentNumber(studentNumber), request)
                .isEmpty()) {
            throw new GeneralException(MemberErrorCode.MEMBER_ALREADY_LINKED);
        }

        linkAttemptLimiter.recordFailure(authUserId);
        throw new GeneralException(MemberErrorCode.MEMBER_LINK_FAILED);
    }

    /*
     * 본인 확인을 통과한 뒤 그 행을 잠그고 auth_user_id를 다시 읽는다 — 근거는
     * MemberRepository.lockAndFindAuthUserId의 주석에 있다. 1차 후보 조회와 이 재확인 사이에
     * 다른 계정이 같은 행을 가져갔다면 여기서 409로 끊는다.
     */
    private MemberEntity lockForLink(MemberEntity member) {
        if (memberRepository.lockAndFindAuthUserId(member.getId()).isPresent()) {
            throw new GeneralException(MemberErrorCode.MEMBER_ALREADY_LINKED);
        }
        return member;
    }

    private static List<MemberEntity> matching(
            List<MemberEntity> candidates, MemberLinkRequest request) {
        return candidates.stream()
                .filter(
                        candidate ->
                                MemberLinkPolicy.matches(
                                        candidate,
                                        request.stdntNo(),
                                        request.mbrNm(),
                                        request.telno()))
                .toList();
    }

    /*
     * UNIQUE 제약이 최종 방어선이다. uk_mbr_auth_user_id 위반은 flush 시점에야 드러나므로
     * 여기서 잡아 선조회와 같은 409로 옮긴다 (가입의 saveOrTranslateConflict와 같은 방식).
     *
     * **같은 행에 대한 경합은 이 제약이 막지 못한다** — 그쪽은 lockForLink가 끊으며 근거는
     * MemberRepository.lockAndFindAuthUserId의 주석에 있다. 여기 걸리는 것은 한 계정이 서로
     * 다른 두 명부 행에 동시에 붙으려는 경우다(학번이 다른 두 요청을 나란히 보내는 경우).
     *
     * 제약명을 가리지 않는 것은 이 UPDATE가 어길 수 있는 UNIQUE가 하나뿐이기 때문이다.
     * 학번은 엔티티에서 updatable = false로 잠겨 있어 이 경로로는 바뀌지 않는다.
     */
    private void flushOrTranslateLinkConflict() {
        try {
            memberRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            throw new GeneralException(MemberErrorCode.MEMBER_ALREADY_LINKED);
        }
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
     * 운영진의 회원 정보 수정 (#77).
     *
     * 바꿀 수 있는 것은 요청 DTO가 담은 여섯 필드뿐이라 여기에 "이 필드는 무시한다"는 분기가
     * 없다 — 등급·상태·학번은 애초에 손에 들어오지 않는다. 그것이 이 API의 계약이며, 학번은
     * 엔티티까지 updatable = false로 잠겨 있어 두 겹으로 막힌다.
     *
     * 조회를 findWithGradeAndStatusById로 하는 것은 응답이 상세와 같은 모양이라 등급·상태의
     * 코드·명칭이 필요하기 때문이다(단건 조회와 같은 이유).
     *
     * **flush를 명시적으로 부른다.** mdfcn_dt는 JPA Auditing이 UPDATE 직전에 채우는데,
     * 트랜잭션이 끝날 때까지 flush가 미뤄지면 응답에 실리는 updatedAt이 수정 전 값이 된다 —
     * 화면이 방금 저장한 항목만 예전 시각으로 그리게 된다.
     */
    @Override
    @Transactional
    public MemberDetailResponse updateMember(Long memberId, MemberUpdateRequest request) {
        MemberEntity member =
                memberRepository
                        .findWithGradeAndStatusById(memberId)
                        .orElseThrow(() -> new GeneralException(MemberErrorCode.MEMBER_NOT_FOUND));

        String departmentName = trimToNull(request.departmentName());
        validateAcademicProfile(member, departmentName, request.academicYear());

        member.updateBasicInfo(
                // gen_no는 NOT NULL이라 '지움'이 없다. 미배정은 가입과 같은 0 센티널이다
                request.generationNumber() == null
                        ? UNASSIGNED_GENERATION_NUMBER
                        : request.generationNumber(),
                request.name().trim(),
                departmentName,
                request.academicYear(),
                trimToNull(request.phoneNumber()),
                trimToNull(request.email()));
        memberRepository.flush();

        return MemberDetailResponse.of(
                member,
                currentRolesOf(List.of(member)).getOrDefault(memberId, List.of()),
                recentChangesOf(memberId));
    }

    /*
     * 본인의 회원 정보 수정 (#77).
     *
     * memberId는 컨트롤러가 @CurrentMember에서 꺼내 넘긴 값이라 **언제나 인증 주체 본인**이다.
     * 요청에도 경로에도 대상을 지정할 자리가 없으므로 여기서 '본인인가'를 다시 검사하지 않는다 —
     * 검사할 다른 값 자체가 들어오지 않는다.
     *
     * 기수·이메일은 요청에 없으므로 현재 값을 그대로 다시 넣는다. updateBasicInfo가 여섯 필드를
     * 한꺼번에 받는 메서드라 두 값을 '건드리지 않음'으로 표현하는 방법이 이것뿐이며, 엔티티에
     * 본인용 부분 수정 메서드를 하나 더 두면 '어느 필드를 바꿀 수 있는가'가 DTO와 엔티티 두
     * 곳에 적히게 된다.
     */
    @Override
    @Transactional
    public MemberProfileResponse updateMyProfile(Long memberId, MemberSelfUpdateRequest request) {
        MemberEntity member =
                memberRepository
                        .findById(memberId)
                        .orElseThrow(() -> new GeneralException(MemberErrorCode.MEMBER_NOT_FOUND));

        String departmentName = trimToNull(request.departmentName());
        validateAcademicProfile(member, departmentName, request.academicYear());

        member.updateBasicInfo(
                member.getGenerationNumber(),
                request.name().trim(),
                departmentName,
                request.academicYear(),
                trimToNull(request.phoneNumber()),
                member.getEmail());
        memberRepository.flush();

        return MemberProfileResponse.of(
                member, findCurrentRoles(memberId), authorityPolicy.capabilityListOf(memberId));
    }

    /*
     * 담당자 후보 목록 (#76). 대상 판정은 단건판(findAssignableMember)과 같은
     * UNASSIGNABLE_STATUS_CODES를 쓴다 — 규칙이 두 벌이 되면 목록과 등록이 갈린다.
     *
     * requiredAuthority가 있으면 그 권한을 오늘 행사할 수 있는 회원으로 한 번 더 좁힌다
     * (#101) — 업무·회의 담당자는 국장 이상만 골라야 하는데, 그 판정은
     * AuthorityPolicy.memberIdsWithAuthority 하나로만 한다. 동아리 규모라 회원 목록을
     * 메모리에서 걸러도 무리가 없고, 그래야 findAllAssignable의 정렬(이름순)이 그대로 유지된다
     * — DB 쪽에서 IN 절로 다시 걸러 별도 정렬을 하면 두 쿼리의 결과 순서가 갈릴 수 있다.
     *
     * requiredAuthority가 없으면 쿼리는 두 번이다(후보 목록 · 현재 역할). 대표 역할명을
     * 채우려면 역할이 필요한데, 후보마다 조회하면 그대로 N+1이라 목록과 같은 방식으로 한 번에
     * 모아 온다. requiredAuthority가 있으면 회원 ID 조회가 더해져 세 번이다.
     */
    @Override
    @Transactional(readOnly = true)
    public List<AssignableMemberResponse> findAssignableMembers(AuthorityCode requiredAuthority) {
        List<MemberEntity> candidates =
                memberRepository.findAllAssignable(UNASSIGNABLE_STATUS_CODES);

        if (requiredAuthority != null) {
            Set<Long> authorizedIds =
                    new HashSet<>(authorityPolicy.memberIdsWithAuthority(requiredAuthority));
            candidates =
                    candidates.stream().filter(m -> authorizedIds.contains(m.getId())).toList();
        }

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

    /*
     * 재학 회원의 학과·학년 필수 (#77). 판정은 가입·CSV 이관과 **같은 한 벌**
     * (AcademicProfilePolicy)이며 여기서 규칙을 다시 적지 않는다 — 두 벌이 되면 가입에서
     * 막히는 값이 수정에서 통과한다.
     *
     * 상태를 요청이 아니라 회원 행에서 읽는 것이 가입과 갈리는 유일한 지점이다. 상태는 이
     * API로 바꿀 수 없으므로 요청 본문에 없고, 따라서 요청 DTO의 @AssertTrue로는 닿지 않는다.
     *
     * **학번 누락은 이 경로에서 보지 않는다.** mbr.stdnt_no는 updatable = false라 수정 요청에
     * 필드가 없고(데이터사전 ssccops#74 '가입 후 변경 불가'), 학번 없이 이관된 재학 회원의
     * 연락처를 고치려는 정상 요청까지 막게 된다 — 고칠 수 없는 값을 이유로 거절하는 셈이다.
     *
     * 기준 코드 테이블에 enum 밖의 상태가 늘어난 경우(from이 null)는 규칙 밖으로 둔다 —
     * 조건부 필수는 재학 하나에만 걸리는 규칙이라 모르는 상태를 재학처럼 다룰 근거가 없다.
     */
    private static void validateAcademicProfile(
            MemberEntity member, String departmentName, Integer academicYear) {
        MemberStatusCode statusCode = MemberStatusCode.from(member.getMembershipStatus().getCode());
        if (statusCode == null) {
            return;
        }

        boolean missingUpdatableField =
                AcademicProfilePolicy.missingRequiredFields(
                                statusCode, member.getStudentNumber(), departmentName, academicYear)
                        .stream()
                        .anyMatch(
                                field ->
                                        field
                                                != AcademicProfilePolicy.AcademicField
                                                        .STUDENT_NUMBER);

        if (missingUpdatableField) {
            throw new GeneralException(MemberErrorCode.ACADEMIC_PROFILE_REQUIRED);
        }
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
     * **합치고 정렬하는 규칙은 여기에 없다** — MemberChangeHistoryAssembler가 갖는다 (#82).
     * 통합 이력 조회와 같은 한 벌을 쓰므로 상세 카드의 첫 줄과 이력 화면의 첫 줄이 갈리지
     * 않는다. 이 메서드가 정하는 것은 두 가지뿐이다: 어느 출처를 읽는가와 몇 건에서 자르는가.
     *
     * 역할을 빈 목록으로 넘기는 것은 상세가 현재 역할을 roles 필드로 따로 싣기 때문이다.
     * 최근 변경 세 칸을 지난 임기의 부여·종료가 채우면 등급·상태의 최근 변화가 밀려난다 —
     * 역할의 시간축은 통합 이력 화면에서 본다.
     */
    private List<MemberChangeHistoryResponse> recentChangesOf(Long memberId) {
        Pageable limit = PageRequest.of(0, RECENT_CHANGE_LIMIT);

        return MemberChangeHistoryAssembler.merge(
                        memberGradeHistoryRepository.findByMemberIdOrderByCreatedAtDescIdDesc(
                                memberId, limit),
                        memberStatusHistoryRepository.findByMemberIdOrderByCreatedAtDescIdDesc(
                                memberId, limit),
                        List.of(),
                        clock.getZone())
                .stream()
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
     * **기록 자체는 MemberInitialHistoryRecorder가 한다** — CSV 이관(#85)도 같은 이력을 남겨야
     * 해서 꺼냈다. 여기 남는 것은 가입 경로가 정하는 두 값뿐이다: 변경자는 **본인**이고(운영진이
     * 개입한 변경이 아니라 본인 신청으로 생긴 값이다) 사유는 '회원가입'이다. 이관은 같은 자리에
     * 요청한 운영자와 'CSV 이관'을 넣는다.
     */
    private void recordInitialHistories(
            MemberEntity member, MemberGradeEntity grade, MemberStatusEntity status) {
        initialHistoryRecorder.record(
                member, grade, status, member.getJoinDate(), SIGNUP_HISTORY_REASON, member);
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
