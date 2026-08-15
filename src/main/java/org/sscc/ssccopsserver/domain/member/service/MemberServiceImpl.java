package org.sscc.ssccopsserver.domain.member.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.code.MemberGradeCode;
import org.sscc.ssccopsserver.domain.member.code.MemberStatusCode;
import org.sscc.ssccopsserver.domain.member.code.error.MemberErrorCode;
import org.sscc.ssccopsserver.domain.member.dto.MemberProfileResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberRoleResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberSignupRequest;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberGradeEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberGradeHistoryEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberStatusEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberStatusHistoryEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeHistoryRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusHistoryRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
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

    // 최초 등급·상태 이력의 변경 사유. 이력만 봐도 운영진의 조정이 아니라 가입임을 알 수 있어야 한다
    private static final String SIGNUP_HISTORY_REASON = "회원가입";

    /*
     * 동시 가입 요청이 UNIQUE 제약에 걸렸을 때 어느 쪽인지 가리기 위한 제약명.
     * 드라이버가 예외 메시지에 제약명을 담아 주므로 그것으로 판별한다.
     */
    private static final String AUTH_USER_ID_CONSTRAINT = "uk_mbr_auth_user_id";
    private static final String STUDENT_NUMBER_CONSTRAINT = "uk_mbr_student_number";

    private final MemberRepository memberRepository;
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

        /*
         * 가입 직후에는 어떤 역할도 부여되지 않는다 — 역할 배정은 운영진의 별도 절차다.
         * 역할이 없으면 권한도 없으므로 capabilities도 빈 목록이다(굳이 조회하지 않는다).
         */
        return MemberProfileResponse.of(saved, List.of(), List.of());
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
     * 기준 코드는 data.sql이 매 기동마다 시드하므로 없을 수 없다. 없다면 사용자 입력 문제가
     * 아니라 시드가 깨진 것이라 400이 아니라 500으로 드러나야 한다.
     */
    private static IllegalStateException missingSeed(String codeGroupName, String code) {
        return new IllegalStateException(
                "%s 기준 코드가 시드되어 있지 않습니다: %s".formatted(codeGroupName, code));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
