package org.sscc.ssccopsserver.domain.member.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.member.code.MemberChangeField;
import org.sscc.ssccopsserver.domain.member.code.MemberChangeType;
import org.sscc.ssccopsserver.domain.member.entity.MemberChangeHistoryEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberGradeHistoryEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleAssignmentEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberStatusHistoryEntity;

/*
 * 회원 변경 이력 한 줄. 회원 상세의 '최근 변경'(#76)과 통합 이력 조회(#82,
 * GET /v1/members/{memberId}/histories)가 **같은 record**를 쓴다.
 *
 * 네 출처(mbr_grd_hstry · mbr_stts_hstry · mbr_role_rel · mbr_chg_hstry)를 **같은 모양**으로
 * 굳혀 한 목록에 섞는다. 화면이 세 배열을 받아 스스로 합치면 정렬 규칙(crt_dt 역순)이 서버와 갈리고,
 * 자르는 개수도 두 곳에서 정해진다. 상세의 최근 3건과 이력 화면이 한 벌의 변환·정렬을
 * 공유하는 것도 같은 이유이며, 그 합치는 자리는 MemberChangeHistoryAssembler다.
 *
 * 이전 값(previous*)은 가입 시점의 최초 부여에서 NULL이다 — 그때는 등급도 상태도 없었다는
 * 사실 그대로이며, 화면은 이 자리를 '신규'로 그린다. 역할도 같은 어휘를 쓴다: 부여는
 * previous*가 비고(→ 역할), 종료는 new*가 빈다(역할 →).
 *
 * changedBy는 그 변경을 한 사람이다. 가입으로 생긴 최초 이력에서는 본인이며, 운영진이
 * 바꾼 이력에서는 그 운영진이다. 이름까지 함께 내리는 것은 등급·상태와 같은 이유다 —
 * 식별자만 주면 화면이 이름을 얻으려 회원을 한 명씩 더 조회하게 된다.
 *
 * **역할 항목(ROLE_ASSIGNED·ROLE_ENDED)의 changedBy는 언제나 null이다.** mbr_role_rel에는
 * 변경자 컬럼(chnrg_mbr_id)이 없어 "누가 부여했는가"를 답할 근거가 데이터에 없기 때문이다.
 * 요청자나 회원 자신을 대신 채우면 이력이 사실이 아닌 것을 말하게 되고, 그 순간 이 목록은
 * 근거로 쓸 수 없다. 화면은 이 자리를 '-'로 그린다 (#82).
 *
 * ── 프로필 변경(#226)이 이 모양에 들어오는 방식 ──────────────
 * mbr_chg_hstry의 값에는 **코드/명 구분이 없다.** 학번 '20200001'은 코드도 표시명도 아니고 그냥
 * 값이라, previousCode·newCode를 비우고 previousName·newName만 채운다 — 화면이 값을 그리는
 * 자리가 등급·상태와 같아야 한 목록으로 읽히기 때문이다(코드 자리는 원래도 상세 이동용 링크
 * 재료라 값이 없으면 그리지 않는다).
 *
 * 대신 **changeField·changeFieldName 두 칸이 늘었다.** 등급·상태·역할은 changeType이 곧 무엇이
 * 바뀌었는지지만, 프로필은 한 종류에 아홉 항목이 섞여 있어 changeType만으로는 "학번이 바뀌었다"를
 * 말할 수 없다. 코드와 표시명을 한 객체로 감싸지 않고 두 칸으로 편 것은 이 record가 이미
 * previousCode·previousName 쌍으로 '코드 + 표시명'을 표현하고 있기 때문이다 — 한 응답 안에서
 * 같은 뜻을 두 가지 모양으로 담으면 화면이 자리마다 다르게 읽어야 한다. 등급·상태·역할 행에서는
 * 둘 다 null이다.
 *
 * appliedDate·changeReason도 프로필 행에서는 null이다. 그 컬럼을 두지 않기로 했기 때문이며
 * (ssccops#162 설계 노트), 이름·연락처는 고친 순간이 곧 적용이고 사유를 물을 자리도 없다.
 *
 * appliedDate는 '언제부터 적용되는가'이고 createdAt은 '언제 기록됐는가'다. 목록 정렬은
 * 기록 시각으로 한다 — 소급 적용된 변경이 나중에 입력돼도 입력 순서대로 쌓여야
 * '최근 변경'이라는 말과 어긋나지 않는다. 역할만은 두 값이 같다: mbr_role_rel의 crt_dt는
 * '행이 만들어진 시각'이라 종료 사건의 발생 시각이 될 수 없어(종료는 같은 행의 UPDATE다),
 * 이슈가 정한 대로 role_bgng_ymd·role_end_ymd를 발생 시각으로 삼는다. 날짜를 시각으로
 * 옮길 때 쓰는 시간대는 주입된 Clock의 것이다(AP-12 — 서비스 표준 시간대 Asia/Seoul).
 */
public record MemberChangeHistoryResponse(
        MemberChangeType changeType,
        MemberChangeField changeField,
        String changeFieldName,
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
                null,
                null,
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
                null,
                null,
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

    /*
     * 역할 부여 (mbr_role_rel · role_bgng_ymd) — "아무것도 아니었다가 이 역할이 됐다".
     *
     * 코드 자리에 역할 식별자(role_id)를 담는다. 등급·상태는 코드 문자열이 PK지만 역할은
     * IDENTITY라 환경마다 다른 숫자이고, 이슈가 정한 대로 화면은 이 값으로 역할 상세로
     * 이동한다. 표시 명칭은 role 테이블의 role_nm이며 자바 코드에 적지 않는다 — 기준정보에서
     * 역할 이름을 바꾸면 이력 표시도 따라와야 한다.
     *
     * 사유(changeReason)는 null이다. mbr_role_rel에 사유 컬럼이 없다.
     */
    public static MemberChangeHistoryResponse roleAssigned(
            MemberRoleAssignmentEntity assignment, ZoneId zone) {
        return new MemberChangeHistoryResponse(
                MemberChangeType.ROLE_ASSIGNED,
                null,
                null,
                null,
                null,
                roleCode(assignment),
                assignment.getRole().getName(),
                assignment.getRoleStartDate(),
                null,
                null,
                null,
                occurredAt(assignment.getRoleStartDate(), zone));
    }

    /*
     * 역할 종료 (mbr_role_rel · role_end_ymd) — "이 역할이었다가 아니게 됐다".
     *
     * 종료일이 채워진 배정에서만 만든다. 종료는 행 삭제가 아니라 role_end_ymd를 채우는
     * 것이므로(#81) 같은 행이 부여 한 줄과 종료 한 줄을 낳는다.
     */
    public static MemberChangeHistoryResponse roleEnded(
            MemberRoleAssignmentEntity assignment, ZoneId zone) {
        return new MemberChangeHistoryResponse(
                MemberChangeType.ROLE_ENDED,
                null,
                null,
                roleCode(assignment),
                assignment.getRole().getName(),
                null,
                null,
                assignment.getRoleEndDate(),
                null,
                null,
                null,
                occurredAt(assignment.getRoleEndDate(), zone));
    }

    /*
     * 회원 정보 변경 (mbr_chg_hstry · #226) — "이 항목이 이 값에서 저 값으로 바뀌었다".
     *
     * 값 두 칸(previousName·newName)은 각각 null일 수 있고 그것이 사실 그대로다: 비어 있던
     * 값을 채웠으면 이전이 null이고, 지웠으면 이후가 null이다. 빈 문자열로 바꿔 채우지 않는다 —
     * 학번을 지우면 NULL로 저장한다는 규칙을 이력이 뒤집어 말하게 된다.
     *
     * 표시명은 자바 enum이 갖는다(MemberChangeField). 등급·상태의 표시명이 기준 코드 테이블에서
     * 오는 것과 갈리는 지점이며, 근거는 그 enum 주석에 있다 — 항목은 mbr의 컬럼이라 운영 중에
     * 늘지 않는다.
     */
    public static MemberChangeHistoryResponse from(MemberChangeHistoryEntity history) {
        return new MemberChangeHistoryResponse(
                MemberChangeType.PROFILE,
                history.getChangeField(),
                history.getChangeField().getDisplayName(),
                null,
                history.getPreviousContent(),
                null,
                history.getNewContent(),
                null,
                null,
                changedById(history.getChangedBy()),
                changedByName(history.getChangedBy()),
                history.getCreatedAt());
    }

    private static String roleCode(MemberRoleAssignmentEntity assignment) {
        return String.valueOf(assignment.getRole().getId());
    }

    // 역할 사건의 발생 시각은 날짜뿐이라 그날의 시작으로 굳힌다 (같은 날의 등급·상태보다 앞선다)
    private static Instant occurredAt(LocalDate date, ZoneId zone) {
        return date.atStartOfDay(zone).toInstant();
    }

    // 변경자는 nullable이다 — 배치나 이관으로 생긴 이력에는 사람이 없다
    private static Long changedById(MemberEntity changedBy) {
        return changedBy == null ? null : changedBy.getId();
    }

    private static String changedByName(MemberEntity changedBy) {
        return changedBy == null ? null : changedBy.getName();
    }
}
