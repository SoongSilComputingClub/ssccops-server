package org.sscc.ssccopsserver.domain.member.dto;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/*
 * 회원 등급 **일괄** 변경 요청 (POST /v1/members/grade-changes, #338).
 *
 * 대상 회원 목록과 **바꿀 값 하나**를 받는다. 회원마다 다른 등급을 지정하는 모양(행 배열)을
 * 고르지 않은 것은, 이 API가 나온 자리가 "명부에서 10명을 골라 정회원으로 올린다"이기 때문이다 —
 * 회원마다 값이 다르다면 그것은 일괄 조작이 아니라 한 명짜리 API를 여러 번 부르는 일이고,
 * 그 경로는 이미 있다. 적용 일자·사유도 하나를 공유한다(같은 결정으로 함께 바뀐 사람들이다).
 *
 * 값 필드의 이름(aftrMbrGrdCd·grdAplcnYmd·grdChgRsnCn)과 그 규칙은 한 명짜리
 * MemberGradeChangeRequest와 **글자 그대로 같다.** 서비스가 이 요청을 그 record로 옮겨 담아
 * 한 명짜리 로직에 그대로 넘기기 때문이며, 이름이 갈리면 웹이 같은 시트에서 두 어휘를 쓰게 된다.
 * 등급 코드를 enum이 아니라 문자열로 받는 이유도 그쪽 주석과 같다(INVALID_CODE_VALUE를 내리기 위해).
 *
 * ── 왜 상한이 100이고 왜 전용 오류 코드가 아닌가 ───────────────────
 * 100은 회원 목록 조회의 최대 페이지 크기(MemberSearchCondition.MAX_SIZE · AP-13)와 같은 값이다.
 * 화면에서 실제로 한 번에 고를 수 있는 최대 인원이 '지금 보고 있는 한 페이지 전체'라, 그보다 큰
 * 요청은 화면이 만든 것이 아니라 명부 전체를 도는 스크립트다. 상한이 없으면 요청 하나가 130명
 * 전부의 등급을 바꾸고 이력 130줄을 남기는데, 그 되돌리기는 사람 손으로 해야 한다.
 *
 * 넘기면 400 VALIDATION_FAILED이며 전용 오류 코드를 새로 만들지 않는다 — 운영관리 API 정의서
 * 03_오류_코드에 없는 코드를 늘리지 않는다는 것이 이 저장소의 태도이고(INVALID_CURSOR·
 * ROLE_PERIOD_INVALID와 같은 판단), 화면이 안내할 문장은 code가 아니라 이 메시지가 전한다.
 *
 * 목록에 같은 회원이 두 번 실려 와도 거절하지 않는다 — 서비스가 앞의 것만 남기고 접는다
 * (MemberBulkChangeServiceImpl 주석). 화면의 체크박스가 만든 목록에 중복이 섞이는 것은 실수이지
 * 막아야 할 요청이 아니다.
 */
public record MemberBulkGradeChangeRequest(
        @NotEmpty(message = "대상 회원은 한 명 이상이어야 합니다.")
                @Size(
                        max = MemberBulkGradeChangeRequest.MAX_TARGETS,
                        message =
                                "한 번에 변경할 수 있는 회원은 "
                                        + MemberBulkGradeChangeRequest.MAX_TARGETS
                                        + "명까지입니다.")
                List<@NotNull(message = "대상 회원 id는 비어 있을 수 없습니다.") Long> mbrIds,
        @NotBlank(message = "변경할 등급 코드는 필수입니다.") String aftrMbrGrdCd,
        LocalDate grdAplcnYmd,
        @Size(max = 500, message = "등급 변경 사유는 500자 이하여야 합니다.") String grdChgRsnCn) {

    /** 한 요청의 대상 상한. 회원 목록 한 페이지의 최대 크기와 같은 값이다 (AP-13) */
    public static final int MAX_TARGETS = 100;

    /** 한 명짜리 로직에 그대로 넘기기 위한 변환. 값 셋은 대상 전원이 공유한다 */
    public MemberGradeChangeRequest toSingleRequest() {
        return new MemberGradeChangeRequest(aftrMbrGrdCd, grdAplcnYmd, grdChgRsnCn);
    }
}
