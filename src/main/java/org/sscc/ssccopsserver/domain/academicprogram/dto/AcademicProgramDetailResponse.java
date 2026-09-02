package org.sscc.ssccopsserver.domain.academicprogram.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramStatus;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/*
 * 기획안·활동 상세 응답(#131). 생성(POST) 응답과 단건 조회(GET) 응답이 같은 모양이다 —
 * 등록 직후 화면이 재조회 없이 같은 화면을 그릴 수 있어야 한다(work 도메인과 같은 판단).
 *
 * title·eventBgngDt·eventEndDt·plcNm은 acdm_actv이 아니라 event 컬럼이지만, 클라이언트가
 * 두 번 호출하지 않도록 여기서 합성해 내려준다(학술관리_API설계.md 베이스 경로 절).
 *
 * formId·formReceiptStatus는 연결된 폼에서 파생한다(#186) — 승인 이관(#148) 전 활동은 폼이
 * 없어 둘 다 null, APPROVED 이상은 event에 연결된 모집 폼의 id와 파생 접수 상태
 * (FormReceiptPolicy.receiptStatusOf, AcademicProgramTransitionResponse.formReceiptStatus와
 * 같은 문자열)다. 데이터 정합성이 깨져 폼이 연결 안 된 활동도 null로 안전하게 내려간다.
 * 판정은 Clock을 주입받는 FormReceiptPolicy가 하므로 정적 팩토리가 직접 부를 수 없어
 * 서비스가 계산해 인자로 넘긴다(#186). progress도 언제나 0이다 — Session 엔티티가 아직 없다(#135).
 */
public record AcademicProgramDetailResponse(
        Long academicProgramId,
        Long eventId,
        String title,
        OffsetDateTime eventBgngDt,
        OffsetDateTime eventEndDt,
        String plcNm,
        String typeCd,
        String typeNm,
        AcademicProgramStatus sttsCd,
        String goalCn,
        String prepCn,
        String schdlCn,
        Integer pscpMinCnt,
        Integer pscpMaxCnt,
        Long prpsrMbrId,
        String prpsrMbrNm,
        Long leadrMbrId,
        String leadrMbrNm,
        boolean isLeader,
        boolean isProposer,
        Long formId,
        String formReceiptStatus,
        AcademicProgramProgressResponse progress,
        int curriculumItemCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static AcademicProgramDetailResponse of(
            AcademicProgramEntity academicProgram,
            int curriculumItemCount,
            MemberEntity viewer,
            Long formId,
            String formReceiptStatus) {
        EventEntity event = academicProgram.getEvent();
        MemberEntity proposer = academicProgram.getProposer();
        MemberEntity leader = academicProgram.getLeader();

        return new AcademicProgramDetailResponse(
                academicProgram.getId(),
                event.getId(),
                event.getTitle(),
                toOffsetDateTime(event.getBeginAt()),
                toOffsetDateTime(event.getEndAt()),
                event.getPlaceName(),
                academicProgram.getType().getCode(),
                academicProgram.getType().getName(),
                academicProgram.getStatus(),
                academicProgram.getGoalContent(),
                academicProgram.getPrepContent(),
                academicProgram.getScheduleText(),
                academicProgram.getCapacityMinCount(),
                academicProgram.getCapacityMaxCount(),
                proposer.getId(),
                proposer.getName(),
                leader == null ? null : leader.getId(),
                leader == null ? null : leader.getName(),
                leader != null && leader.getId().equals(viewer.getId()),
                proposer.getId().equals(viewer.getId()),
                formId,
                formReceiptStatus,
                AcademicProgramProgressResponse.zero(),
                curriculumItemCount,
                toOffsetDateTime(academicProgram.getCreatedAt()),
                toOffsetDateTime(academicProgram.getUpdatedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
