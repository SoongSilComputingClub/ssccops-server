package org.sscc.ssccopsserver.domain.academicprogram.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramStatus;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/*
 * 목록(#131 · GET /v1/academic-programs) 카드 한 장. 스터디·프로젝트 목록 화면, 대시보드
 * 상태별 카운트 겸용이다.
 *
 * progressRatio는 상세의 progress.ratio와 같은 값이다 — Session 엔티티가 아직 없어(#135)
 * 언제나 0이다.
 *
 * ── 모집 카드에 필요한 값이 #483에서 늘었다 ───────────────────────────────
 * lms "모집 관리"가 카드에 상태 배지·접수 기간·정원·지원 건수·문항 버전·승인일을 그리는데,
 * 그전까지 이 응답에는 그중 하나도 없어 화면이 카드마다 활동 상세를 한 번 더 불러야 했다.
 * 필드 추가는 OpenAPI 하위 호환 게이트(#412)가 막지 않는다.
 *
 * **배지는 sttsCd가 아니라 formReceiptStatus로 그린다.** 학술국장이 미래 시작일로 모집을
 * 시작하면 활동은 곧바로 ONGOING이지만 접수는 아직 열리지 않았고(SCHEDULED), 화면의
 * "모집 시작 전"은 그 구간까지 포함한다 — DRAFT·SCHEDULED가 '모집 시작 전', ACCEPTING이
 * '접수중', EXPIRED·CLOSED가 '접수 종료'다.
 *
 * approvedAt은 acdm_actv.crt_dt다. 승인이 곧 생성이므로(AcademicProgramStatus 주석 —
 * "행은 항상 APPROVED로 태어난다") 승인 일시를 담을 컬럼을 따로 두지 않는다.
 *
 * applicationCount는 접수 전에도 **0을 그대로** 내린다 — "미모집" 같은 대체값을 서버가 만들지
 * 않는다(#198의 eventPtcpId null 규칙과 같은 태도). 화면의 '-'는 화면이 그린다.
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
        Long formId,
        String formReceiptStatus,
        OffsetDateTime rcptBgngDt,
        OffsetDateTime rcptEndDt,
        Integer qitemVer,
        Integer pscpMinCnt,
        Integer pscpMaxCnt,
        long applicationCount,
        OffsetDateTime approvedAt,
        boolean isLeader) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    /*
     * 폼에서 오는 값(formId·접수 기간·문항 버전)은 여기서 직접 읽는다 — 이미 event를 손에
     * 쥐고 있어 서비스가 한 번 더 풀어 넘길 이유가 없다. 반면 formReceiptStatus는 주입된
     * Clock을 보는 FormReceiptPolicy만이 답할 수 있고 applicationCount는 폼 도메인의 집계라
     * 정적 팩토리가 부를 수 없으므로 서비스가 계산해 넘긴다(상세 응답과 같은 갈림이다).
     *
     * 폼이 없는 활동(이관 전이거나 정합성이 깨진 경우)은 폼에서 오는 값이 전부 null이다.
     */
    public static AcademicProgramSummaryResponse of(
            AcademicProgramEntity academicProgram,
            MemberEntity viewer,
            String formReceiptStatus,
            long applicationCount) {
        EventEntity event = academicProgram.getEvent();
        MemberEntity leader = academicProgram.getLeader();
        FormEntity form = event.getForm();

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
                form == null ? null : form.getId(),
                formReceiptStatus,
                form == null ? null : toOffsetDateTime(form.getReceiptBeginAt()),
                form == null ? null : toOffsetDateTime(form.getReceiptEndAt()),
                form == null ? null : form.getQuestionVersion(),
                academicProgram.getCapacityMinCount(),
                academicProgram.getCapacityMaxCount(),
                applicationCount,
                toOffsetDateTime(academicProgram.getCreatedAt()),
                leader != null && leader.getId().equals(viewer.getId()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
