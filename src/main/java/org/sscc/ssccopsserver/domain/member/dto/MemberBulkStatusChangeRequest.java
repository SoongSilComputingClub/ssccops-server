package org.sscc.ssccopsserver.domain.member.dto;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/*
 * 회원 상태 **일괄** 변경 요청 (POST /v1/members/status-changes, #338).
 *
 * 대상 목록·상한·중복 처리는 MemberBulkGradeChangeRequest와 같고 근거도 그쪽 주석에 있다.
 * 값 필드는 한 명짜리 MemberStatusChangeRequest와 글자 그대로 같으며 서비스가 그 record로 옮겨
 * 담아 한 명짜리 로직에 넘긴다.
 *
 * ── sttsEndPrnmntYmd를 일괄에서도 받는 이유 ────────────────────────
 * 종료 예정일은 휴학·군휴학에만 뜻이 있고 그 밖의 상태에 실려 오면 400이다(그 규칙은 한 명짜리와
 * 같은 자리에서 판정한다). 일괄에서 이 값이 쓸모없어 보일 수 있지만 그렇지 않다 — 군휴학으로
 * 함께 들어가는 인원의 복학 예정 학기는 같은 날짜인 경우가 대부분이고, 값을 받지 않으면 그
 * 사람들만 한 명씩 다시 들어가야 해서 이 API가 없애려던 일이 그대로 돌아온다.
 *
 * 단, 이 값은 **대상 전원이 공유한다.** 사람마다 다른 복학일이 필요하면 그것은 한 명짜리
 * 경로의 일이다.
 *
 * ── 상태 일괄 변경은 되돌리기가 더 어렵다 ──────────────────────────
 * 탈퇴·제명은 등급 강등과 달리 조직을 떠나는 전이라, 잘못 고른 30명을 되돌리려면 상태만이 아니라
 * 그 사이에 화면에서 사라진 것들을 사람이 되짚어야 한다. 그래서 이 요청도 이력을 반드시 남기는
 * 한 명짜리 로직 위에 서고(#338의 전제), 남아 있는 역할·담당 업무는 회원별 warnings로 실린다.
 */
public record MemberBulkStatusChangeRequest(
        @NotEmpty(message = "대상 회원은 한 명 이상이어야 합니다.")
                @Size(
                        max = MemberBulkGradeChangeRequest.MAX_TARGETS,
                        message =
                                "한 번에 변경할 수 있는 회원은 "
                                        + MemberBulkGradeChangeRequest.MAX_TARGETS
                                        + "명까지입니다.")
                List<@NotNull(message = "대상 회원 id는 비어 있을 수 없습니다.") Long> mbrIds,
        @NotBlank(message = "변경할 상태 코드는 필수입니다.") String aftrMbrSttsCd,
        LocalDate sttsAplcnYmd,
        LocalDate sttsEndPrnmntYmd,
        @Size(max = 500, message = "상태 변경 사유는 500자 이하여야 합니다.") String sttsChgRsnCn) {

    /** 한 명짜리 로직에 그대로 넘기기 위한 변환. 값 넷은 대상 전원이 공유한다 */
    public MemberStatusChangeRequest toSingleRequest() {
        return new MemberStatusChangeRequest(
                aftrMbrSttsCd, sttsAplcnYmd, sttsEndPrnmntYmd, sttsChgRsnCn);
    }
}
