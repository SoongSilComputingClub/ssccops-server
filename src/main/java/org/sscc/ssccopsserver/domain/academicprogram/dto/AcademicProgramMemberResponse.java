package org.sscc.ssccopsserver.domain.academicprogram.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;
import org.sscc.ssccopsserver.domain.event.entity.EventParticipantEntity;

/*
 * 팀원 명단 항목 (#138 · GET /v1/academic-programs/{academicProgramId}/members).
 *
 * event_ptcp를 학술관리 화면에 그대로 다시 내리는 값이지만 **행사 도메인의
 * EventParticipantResponse를 재사용하지 않는다.** 그쪽은 ResponseMemberSummary를 실어
 * 학번·학과·등급·상태까지 함께 내려주는데, 그 경로는 클래스 레벨 EVENT_MANAGE로 잠겨 있고
 * 이쪽은 **인증만**이다(학술관리_API설계.md §6 — 팀원 명단은 활동 상세 화면의 일부라 팀원
 * 누구나 본다). 같은 DTO를 쓰면 권한이 다른 두 문이 같은 개인정보를 내보내게 되고, 행사 쪽에
 * 컬럼이 하나 늘 때마다 이 공개 경로로 함께 새어 나간다. 그래서 회원 정보는 이름 하나뿐이다.
 *
 * isLeader는 명단 행이 아니라 academic_program.leadr_mbr_id와의 비교에서 온다 — event_ptcp에는
 * 리더 표시가 없고, 있어서도 안 된다(리더는 활동의 속성이지 참가 상태가 아니다). 스터디장이
 * 아직 자기 활동의 참가자로 등록되지 않았다면 이 목록에는 아예 나오지 않는다.
 *
 * joinedAt은 등록 일시(crt_dt)다 — "언제 팀에 들어왔는가"이고, 상태가 대기에서 확정으로 바뀐
 * 시점(mdfcn_dt)이 아니다. 일시는 AP-12에 따라 Asia/Seoul 오프셋을 포함해 내려준다.
 */
public record AcademicProgramMemberResponse(
        Long eventPtcpId,
        Long mbrId,
        String mbrNm,
        EventParticipantStatus ptcpSttsCd,
        boolean isLeader,
        OffsetDateTime joinedAt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static AcademicProgramMemberResponse of(
            EventParticipantEntity participant, AcademicProgramEntity academicProgram) {
        Long memberId = participant.getMember().getId();
        return new AcademicProgramMemberResponse(
                participant.getId(),
                memberId,
                participant.getMember().getName(),
                participant.getStatus(),
                isLeader(academicProgram, memberId),
                toOffsetDateTime(participant.getCreatedAt()));
    }

    private static boolean isLeader(AcademicProgramEntity academicProgram, Long memberId) {
        return academicProgram.getLeader() != null
                && academicProgram.getLeader().getId().equals(memberId);
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
