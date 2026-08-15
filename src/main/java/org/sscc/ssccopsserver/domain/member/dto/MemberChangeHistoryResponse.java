package org.sscc.ssccopsserver.domain.member.dto;

import java.time.Instant;
import java.time.LocalDate;

import org.sscc.ssccopsserver.domain.member.code.MemberChangeType;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberGradeHistoryEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberStatusHistoryEntity;

/*
 * 회원 상세(GET /v1/members/{mbrId})의 '최근 변경' 한 줄 (#76).
 *
 * 등급 이력(mbr_grd_hstry)과 상태 이력(mbr_stts_hstry)을 **같은 모양**으로 굳혀 한 목록에
 * 섞는다. 화면이 두 배열을 받아 스스로 합치면 정렬 규칙(crt_dt 역순)이 서버와 갈리고,
 * 자르는 개수도 두 곳에서 정해진다.
 *
 * 이전 값(previous*)은 가입 시점의 최초 부여에서 NULL이다 — 그때는 등급도 상태도 없었다는
 * 사실 그대로이며, 화면은 이 자리를 '신규'로 그린다.
 *
 * changedBy는 그 변경을 한 사람이다. 가입으로 생긴 최초 이력에서는 본인이며, 운영진이
 * 바꾼 이력에서는 그 운영진이다. 이름까지 함께 내리는 것은 등급·상태와 같은 이유다 —
 * 식별자만 주면 화면이 이름을 얻으려 회원을 한 명씩 더 조회하게 된다.
 *
 * appliedDate는 '언제부터 적용되는가'이고 createdAt은 '언제 기록됐는가'다. 목록 정렬은
 * 기록 시각으로 한다 — 소급 적용된 변경이 나중에 입력돼도 입력 순서대로 쌓여야
 * '최근 변경'이라는 말과 어긋나지 않는다.
 */
public record MemberChangeHistoryResponse(
        MemberChangeType changeType,
        String previousCode,
        String previousName,
        String newCode,
        String newName,
        LocalDate appliedDate,
        String changeReason,
        Long changedByMemberId,
        String changedByName,
        Instant createdAt) {

    public static MemberChangeHistoryResponse from(MemberGradeHistoryEntity history) {
        return new MemberChangeHistoryResponse(
                MemberChangeType.GRADE,
                history.getPreviousGrade() == null ? null : history.getPreviousGrade().getCode(),
                history.getPreviousGrade() == null ? null : history.getPreviousGrade().getName(),
                history.getNewGrade().getCode(),
                history.getNewGrade().getName(),
                history.getAppliedDate(),
                history.getChangeReason(),
                changedById(history.getChangedBy()),
                changedByName(history.getChangedBy()),
                history.getCreatedAt());
    }

    public static MemberChangeHistoryResponse from(MemberStatusHistoryEntity history) {
        return new MemberChangeHistoryResponse(
                MemberChangeType.STATUS,
                history.getPreviousStatus() == null ? null : history.getPreviousStatus().getCode(),
                history.getPreviousStatus() == null ? null : history.getPreviousStatus().getName(),
                history.getNewStatus().getCode(),
                history.getNewStatus().getName(),
                history.getAppliedDate(),
                history.getChangeReason(),
                changedById(history.getChangedBy()),
                changedByName(history.getChangedBy()),
                history.getCreatedAt());
    }

    // 변경자는 nullable이다 — 배치나 이관으로 생긴 이력에는 사람이 없다
    private static Long changedById(MemberEntity changedBy) {
        return changedBy == null ? null : changedBy.getId();
    }

    private static String changedByName(MemberEntity changedBy) {
        return changedBy == null ? null : changedBy.getName();
    }
}
