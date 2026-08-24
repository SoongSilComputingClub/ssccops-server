package org.sscc.ssccopsserver.domain.academicprogram.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramStatus;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/*
 * 목록(#131 · GET /v1/academic-programs) 카드 한 장. 스터디·프로젝트 목록 화면, 대시보드
 * 상태별 카운트 겸용이다.
 *
 * progressRatio는 상세의 progress.ratio와 같은 값이다 — Session 엔티티가 아직 없어(#135)
 * 언제나 0이다.
 */
public record AcademicProgramSummaryResponse(
        Long academicProgramId,
        Long eventId,
        String title,
        String typeCd,
        AcademicProgramStatus sttsCd,
        String leadrMbrNm,
        OffsetDateTime eventBgngDt,
        OffsetDateTime eventEndDt,
        BigDecimal progressRatio,
        boolean isLeader) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static AcademicProgramSummaryResponse of(
            AcademicProgramEntity academicProgram, MemberEntity viewer) {
        EventEntity event = academicProgram.getEvent();
        MemberEntity leader = academicProgram.getLeader();

        return new AcademicProgramSummaryResponse(
                academicProgram.getId(),
                event.getId(),
                event.getTitle(),
                academicProgram.getType().getCode(),
                academicProgram.getStatus(),
                leader == null ? null : leader.getName(),
                toOffsetDateTime(event.getBeginAt()),
                toOffsetDateTime(event.getEndAt()),
                AcademicProgramProgressResponse.zero().ratio(),
                leader != null && leader.getId().equals(viewer.getId()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
