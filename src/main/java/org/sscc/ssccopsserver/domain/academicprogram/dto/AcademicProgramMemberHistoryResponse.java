package org.sscc.ssccopsserver.domain.academicprogram.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.event.code.EventParticipantChangePath;
import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;
import org.sscc.ssccopsserver.domain.event.entity.EventParticipantEntity;
import org.sscc.ssccopsserver.domain.event.entity.EventParticipantStatusHistoryEntity;

/*
 * 팀원 명단 변경 이력 한 줄 (#612 · GET /v1/academic-programs/{academicProgramId}/members/history).
 *
 * event_ptcp_stts_hstry를 학술 화면에 내리는 값이다. 팀원 명단(AcademicProgramMemberResponse)처럼
 * **회원 정보는 이름뿐이다** — 이 경로는 스터디장·학술국장에게만 열리지만, 행사 쪽 DTO의 학번·학과를
 * 싣는 이유가 이 화면에는 없다.
 *
 * - bfrPtcpSttsCd는 처음 명단에 오른 줄이면 null이다(등록).
 * - chgPathSeCd는 어느 화면이 바꿨나다 — 모집 선발 · 행사 참가자 · 팀원 관리. 학술국장은 셋을
 *   다 쓸 수 있어 수행자 이름만으로는 어느 권한으로 한 일인지 알 수 없다.
 * - 일시는 AP-12에 따라 Asia/Seoul 오프셋을 포함한다.
 */
public record AcademicProgramMemberHistoryResponse(
        Long historyId,
        Long eventPtcpId,
        Long mbrId,
        String mbrNm,
        EventParticipantStatus bfrPtcpSttsCd,
        EventParticipantStatus aftrPtcpSttsCd,
        EventParticipantChangePath chgPathSeCd,
        Long prfmrId,
        String prfmrNm,
        OffsetDateTime chgDt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static AcademicProgramMemberHistoryResponse of(
            EventParticipantStatusHistoryEntity history) {
        EventParticipantEntity participant = history.getParticipant();
        return new AcademicProgramMemberHistoryResponse(
                history.getId(),
                participant.getId(),
                participant.getMember().getId(),
                participant.getMember().getName(),
                history.getPreviousStatus(),
                history.getNextStatus(),
                history.getChangePath(),
                history.getPerformer().getId(),
                history.getPerformer().getName(),
                toOffsetDateTime(history.getChangedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
