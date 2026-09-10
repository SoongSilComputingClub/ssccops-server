package org.sscc.ssccopsserver.domain.member.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import org.sscc.ssccopsserver.domain.member.code.MemberStatusCode;
import org.sscc.ssccopsserver.domain.member.service.AcademicProfilePolicy;

/*
 * 회원가입 요청 (POST /v1/members/signup). 필드 구성은 가입 화면의 입력란을 그대로 따른다.
 *
 * 인증 주체가 정하는 값(auth_user_id·이메일)과 서버가 정하는 값(등급 TEMP·가입일)은 받지 않는다 —
 * 요청 본문으로 받으면 남의 계정으로 가입하거나 등급을 스스로 올릴 수 있다.
 *
 * memberStatusCode를 문자열이 아니라 enum으로 받는 것은 기준 코드 밖의 값을 전역 핸들러가
 * INVALID_CODE_VALUE(400)로 잡아 주기 때문이다. 기준 코드에는 있으나 가입 시 고를 수 없는 값
 * (탈퇴·제명 등)은 아래 @AssertTrue가 VALIDATION_FAILED(400)로 걸러낸다.
 *
 * 필드명은 이슈 #21의 API 계약 표를 그대로 따랐다. 응답(MemberProfileResponse)의
 * membershipStatusCode와 접두어가 다른데, 응답 스키마는 #20에서 이미 확정돼 소비 중이라
 * 여기서 바꾸지 않는다.
 */
public record MemberSignupRequest(
        @NotBlank @Size(max = 50) String name,
        @NotBlank @Size(max = 20) String phoneNumber,
        @NotNull MemberStatusCode memberStatusCode,
        @Pattern(regexp = STUDENT_NUMBER_PATTERN, message = "학번은 숫자 8~10자리입니다.")
                String studentNumber,
        @Size(max = 100) String departmentName,
        @Min(1) @Max(4) Integer academicYear,
        @PositiveOrZero Integer generationNumber) {

    /*
     * 학번 형식 (#334). 숫자만 · 8~10자리이고 **빈 값은 통과한다**.
     *
     * 졸업 회원은 학번이 없다. 재학 회원에게만 필수라는 규칙은 아래 isAcademicProfileComplete()가
     * AcademicProfilePolicy를 통해 이미 보므로, 여기에 @NotBlank를 붙이면 그 정책과 두 벌이 되어
     * 졸업 회원 가입이 막힌다. null은 Bean Validation의 @Pattern이 그냥 통과시키지만 가입 화면은
     * 빈 칸을 ""로 보내오므로(MemberControllerTest.graduatedMemberSignsUpWithoutStudentNumber)
     * 정규식 자체가 빈 문자열을 허용해야 한다.
     *
     * 8~10자리인 근거는 명부에 8자리 학번이 실재하기 때문이다(예: 20211725). 10자리로만 좁히면
     * 그 회원들이 자기 학번으로 가입하지 못한다.
     *
     * @Size(max = 20)을 뺀 것은 이 정규식이 길이를 10자로 이미 묶어 두 규칙이 갈릴 자리를 남기지
     * 않기 위해서다(stdnt_no 컬럼은 VARCHAR(20)이라 여전히 넉넉하다). EventCategoryCreateRequest가
     * 길이를 묶는 @Pattern 옆에 @Size를 두지 않는 것과 같다.
     *
     * **이 형식을 계정 연결(MemberLinkRequest.stdntNo)과 CSV 이관 검증에는 넣지 않는다.** 그 둘은
     * 명부에 이미 있는 값을 맞추거나 그대로 들여오는 경로라, 형식으로 거르면 형식 밖의 학번을 가진
     * 본인이 연결하지 못하고 과거 명부의 이관이 멈춘다 (ssccops-web#364도 같은 이유로 뺐다).
     */
    private static final String STUDENT_NUMBER_PATTERN = "^$|^\\d{8,10}$";

    // 기준 코드 위반(@NotNull 미충족 포함)은 다른 검증이 이미 알려주므로 여기서 중복해 실패시키지 않는다
    @AssertTrue(message = "가입 시 선택할 수 없는 회원 상태입니다.")
    public boolean isSignupSelectableStatus() {
        return memberStatusCode == null || memberStatusCode.isSignupSelectable();
    }

    /*
     * 재학 회원만 학번·학과·학년이 필수다. 필드마다 @NotBlank를 걸 수 없어(졸업이면 비어 있어야
     * 한다) 클래스 레벨에서 상태와 함께 본다.
     *
     * 규칙 자체는 AcademicProfilePolicy가 갖는다 (#84) — CSV 이관 검증이 같은 판단을 해야 하는데,
     * 여기에 두면 두 벌이 되어 가입에서 막히는 값이 이관에서는 통과한다.
     */
    @AssertTrue(message = "재학 회원은 학번·학과·학년을 모두 입력해야 합니다.")
    public boolean isAcademicProfileComplete() {
        return AcademicProfilePolicy.isComplete(
                memberStatusCode, studentNumber, departmentName, academicYear);
    }
}
