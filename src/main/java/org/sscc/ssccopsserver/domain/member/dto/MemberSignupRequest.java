package org.sscc.ssccopsserver.domain.member.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
        @Size(max = 20) String studentNumber,
        @Size(max = 100) String departmentName,
        @Min(1) @Max(4) Integer academicYear,
        @PositiveOrZero Integer generationNumber) {

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
