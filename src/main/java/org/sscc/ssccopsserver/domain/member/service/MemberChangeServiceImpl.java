package org.sscc.ssccopsserver.domain.member.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.code.MemberGradeCode;
import org.sscc.ssccopsserver.domain.member.code.MemberStatusCode;
import org.sscc.ssccopsserver.domain.member.code.error.MemberErrorCode;
import org.sscc.ssccopsserver.domain.member.dto.MemberChangeWarningResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberGradeChangeRequest;
import org.sscc.ssccopsserver.domain.member.dto.MemberGradeChangeResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberStatusChangeRequest;
import org.sscc.ssccopsserver.domain.member.dto.MemberStatusChangeResponse;
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
import org.sscc.ssccopsserver.domain.operation.service.SubWorkService;
import org.sscc.ssccopsserver.global.apipayload.code.error.CommonErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

/*
 * 회원 등급·상태 변경과 이력 기록 (#78). 별도 빈으로 나눈 이유는 MemberChangeService 주석에 있다.
 *
 * 두 메서드의 뼈대가 같다 — 회원을 찾고, 코드값을 해석하고, 같은 값이면 거절하고, mbr을 갱신한
 * 뒤 이력을 남긴다. 그런데도 하나로 합치지 않은 것은 두 이력 테이블의 컬럼이 다르기 때문이다
 * (상태에만 stts_end_prnmnt_ymd가 있다). 제네릭으로 묶으면 그 차이가 분기로 되살아난다.
 *
 * 선례는 SubWorkServiceImpl.transitionSubWork다 — 상태 전이와 sub_work_stts_hstry INSERT를
 * 한 트랜잭션에 묶는 자리.
 */
@Service
@RequiredArgsConstructor
public class MemberChangeServiceImpl implements MemberChangeService {

    private final MemberRepository memberRepository;
    private final MemberGradeRepository memberGradeRepository;
    private final MemberStatusRepository memberStatusRepository;
    private final MemberGradeHistoryRepository memberGradeHistoryRepository;
    private final MemberStatusHistoryRepository memberStatusHistoryRepository;
    private final MemberRoleAssignmentRepository memberRoleAssignmentRepository;

    /*
     * 변경 후 회원 상세는 #76의 조립을 그대로 쓴다. 여기서 DTO를 다시 만들면 현재 역할 판정과
     * 최근 변경 이력을 자르는 규칙이 두 벌이 되어 상세 화면과 변경 응답이 갈린다.
     */
    private final MemberService memberService;

    // 탈퇴·제명 경고의 '담당 중인 하위 업무' 건수. 운영 Repository를 직접 부르지 않는다 (AR-07)
    private final SubWorkService subWorkService;

    // 적용 일자의 기본값·미래 판정 기준. 테스트에서 고정할 수 있도록 주입받는다 (ClockConfig)
    private final Clock clock;

    @Override
    @Transactional
    public MemberGradeChangeResponse changeGrade(
            Long memberId, MemberGradeChangeRequest request, MemberEntity changer) {

        MemberEntity member = findMember(memberId);
        MemberGradeCode gradeCode = gradeCodeOf(request.aftrMbrGrdCd());
        LocalDate appliedDate = appliedDateOf(request.grdAplcnYmd());

        /*
         * 변경 전 값은 member.changeMembershipGrade를 부르기 **전에** 붙들어 둔다. 뒤에 읽으면
         * 이미 바뀐 값이라 이력의 bfr_mbr_grd_cd가 aftr와 같아진다.
         */
        MemberGradeEntity previousGrade = member.getMembershipGrade();
        if (previousGrade.getCode().equals(gradeCode.code())) {
            throw new GeneralException(MemberErrorCode.NO_CHANGE);
        }

        MemberGradeEntity newGrade = findGrade(gradeCode);
        member.changeMembershipGrade(newGrade);
        memberGradeHistoryRepository.save(
                MemberGradeHistoryEntity.create(
                        member,
                        previousGrade,
                        newGrade,
                        appliedDate,
                        trimToNull(request.grdChgRsnCn()),
                        changer));

        /*
         * 등급 변경에는 경고가 없다 — 조직을 떠나는 전이가 아니기 때문이다. 그런데도 필드를
         * 비워 채우는 이유는 MemberGradeChangeResponse 주석에 있다.
         */
        return new MemberGradeChangeResponse(memberService.getMemberDetail(memberId), List.of());
    }

    @Override
    @Transactional
    public MemberStatusChangeResponse changeStatus(
            Long memberId, MemberStatusChangeRequest request, MemberEntity changer) {

        MemberEntity member = findMember(memberId);
        MemberStatusCode statusCode = statusCodeOf(request.aftrMbrSttsCd());
        LocalDate appliedDate = appliedDateOf(request.sttsAplcnYmd());
        LocalDate expectedEndDate =
                validatedExpectedEndDate(statusCode, appliedDate, request.sttsEndPrnmntYmd());

        MemberStatusEntity previousStatus = member.getMembershipStatus();
        if (previousStatus.getCode().equals(statusCode.code())) {
            throw new GeneralException(MemberErrorCode.NO_CHANGE);
        }

        MemberStatusEntity newStatus = findStatus(statusCode);
        member.changeMembershipStatus(newStatus);
        memberStatusHistoryRepository.save(
                MemberStatusHistoryEntity.create(
                        member,
                        previousStatus,
                        newStatus,
                        appliedDate,
                        expectedEndDate,
                        trimToNull(request.sttsChgRsnCn()),
                        changer));

        return new MemberStatusChangeResponse(
                memberService.getMemberDetail(memberId), warningsOf(memberId, statusCode));
    }

    /*
     * 조직을 떠나는 전이에서 아직 남아 있는 것들 (#78).
     *
     * **아무것도 정리하지 않는다.** 역할을 자동으로 끝내거나 담당 업무를 재배정하는 것은 운영
     * 규칙이 필요한 판단이라 범위 밖이고, 여기서는 사람이 처리하도록 숫자만 실어 보낸다.
     *
     * 남은 것이 없으면 건수 0짜리 경고를 만들지 않고 아예 빼 버린다 — 화면이 "역할 0건이
     * 있습니다"를 그리지 않게 하려면 서버가 그 줄을 내리지 않는 편이 간단하다.
     *
     * 조회는 mbr을 갱신한 뒤에 한다. 상태 변경 자체가 역할·업무를 건드리지 않으므로 순서가
     * 결과를 바꾸지는 않지만, '변경 후의 상태'를 말하는 응답이라 갱신 뒤가 제자리다.
     */
    private List<MemberChangeWarningResponse> warningsOf(Long memberId, MemberStatusCode toStatus) {
        if (!toStatus.isOrganizationExit()) {
            return List.of();
        }

        List<MemberChangeWarningResponse> warnings = new ArrayList<>();

        long roleCount =
                memberRoleAssignmentRepository.countCurrentByMemberId(
                        memberId, LocalDate.now(clock));
        if (roleCount > 0) {
            warnings.add(MemberChangeWarningResponse.currentRoles(roleCount));
        }

        long subWorkCount = subWorkService.countOngoingByOwner(memberId);
        if (subWorkCount > 0) {
            warnings.add(MemberChangeWarningResponse.assignedSubWorks(subWorkCount));
        }

        return List.copyOf(warnings);
    }

    /*
     * 등급·상태를 함께 끌어온다. findById로 읽으면 둘이 프록시로 남아 '같은 값인가' 판정과
     * 이력의 bfr_* 기록에서 조회가 한 번씩 더 나간다 (DB-13).
     */
    private MemberEntity findMember(Long memberId) {
        return memberRepository
                .findWithGradeAndStatusById(memberId)
                .orElseThrow(() -> new GeneralException(MemberErrorCode.MEMBER_NOT_FOUND));
    }

    /*
     * 적용 일자. 생략하면 서버의 오늘이며, 오늘은 시스템 시각이 아니라 주입된 Clock에서 온다.
     *
     * 미래 일자를 거절하는 근거는 MemberErrorCode.FUTURE_APPLIED_DATE 주석에 있다 —
     * mbr은 이 요청으로 지금 바뀌므로 미래 일자를 받아들이면 이력과 회원이 어긋난다.
     * 과거 일자는 허용한다(소급 입력은 실제로 일어나는 일이다).
     */
    private LocalDate appliedDateOf(LocalDate requested) {
        LocalDate today = LocalDate.now(clock);
        if (requested == null) {
            return today;
        }
        if (requested.isAfter(today)) {
            throw new GeneralException(MemberErrorCode.FUTURE_APPLIED_DATE);
        }
        return requested;
    }

    /*
     * 종료 예정일. 쓸 수 없는 상태에 실려 오면 조용히 버리지 않고 거절한다
     * (MemberStatusChangeRequest 주석 — 이력 행이 잠겨 있어 나중에 채워 넣을 수 없다).
     *
     * 적용 일자보다 앞선 날짜도 거절한다. 시작하기 전에 끝나는 상태는 성립하지 않으며, 같은
     * 날은 허용한다 — 하루짜리 상태가 말이 안 되는 것은 아니다.
     */
    private static LocalDate validatedExpectedEndDate(
            MemberStatusCode statusCode, LocalDate appliedDate, LocalDate expectedEndDate) {

        if (expectedEndDate == null) {
            return null;
        }
        if (!statusCode.allowsExpectedEndDate()) {
            throw new GeneralException(MemberErrorCode.STATUS_END_DATE_NOT_ALLOWED);
        }
        if (expectedEndDate.isBefore(appliedDate)) {
            throw new GeneralException(MemberErrorCode.STATUS_END_DATE_BEFORE_APPLIED);
        }
        return expectedEndDate;
    }

    /*
     * 기준 코드 밖의 값은 400 INVALID_CODE_VALUE다. 요청 DTO가 enum이 아니라 문자열로 받는
     * 것도 이 코드를 내리기 위해서다 (MemberGradeChangeRequest 주석).
     */
    private static MemberGradeCode gradeCodeOf(String code) {
        try {
            return MemberGradeCode.valueOf(code.trim());
        } catch (IllegalArgumentException ex) {
            throw new GeneralException(CommonErrorCode.INVALID_CODE_VALUE);
        }
    }

    /*
     * 상태는 가입 경로처럼 고를 수 있는 값을 좁히지 않는다 (isSignupSelectable을 보지 않는다) —
     * 휴학·탈퇴·제명으로의 전이가 바로 이 API가 존재하는 이유다.
     */
    private static MemberStatusCode statusCodeOf(String code) {
        try {
            return MemberStatusCode.valueOf(code.trim());
        } catch (IllegalArgumentException ex) {
            throw new GeneralException(CommonErrorCode.INVALID_CODE_VALUE);
        }
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
     * enum에는 있는데 mbr_grd·mbr_stts 테이블에 없는 코드는 사용자 입력 문제가 아니라 시드가
     * 깨진 것이라 400이 아니라 500으로 드러나야 한다 (MemberServiceImpl과 같은 판단).
     */
    private static IllegalStateException missingSeed(String seedName, String code) {
        return new IllegalStateException("%s 시드가 없습니다: %s".formatted(seedName, code));
    }

    // 공백만 있는 사유는 없는 것으로 본다 — 빈 문자열이 남으면 화면이 사유가 있는 줄 알고 그린다
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
