package org.sscc.ssccopsserver.domain.academicprogram.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.form.code.FormReceiptStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;

/*
 * 모집 일정 조회·변경 응답 (GET·PATCH .../recruitment/schedule).
 *
 * 저장된 두 일시와 그것에서 파생된 접수 상태를 함께 싣는다. 파생 상태를 같이 내리는 것은
 * 날짜를 고치면 배지가 함께 움직이기 때문이다 — 화면이 rcptBgngDt를 보고 접수 상태를 다시
 * 계산하면 시계가 두 벌이 되고(서버는 Clock, 브라우저는 로컬 시각) "미래로 미뤘는데 여전히
 * 모집 중"으로 보이는 구간이 생긴다. 판정은 FormReceiptPolicy 한 곳이다.
 *
 * formId를 싣는 것은 화면이 같은 카드에서 '신청서 문항 편집'으로 이어 가기 때문이다(이미
 * 활동 상세가 주는 값이지만, 이 응답만 받아도 카드를 다시 그릴 수 있어야 한다).
 *
 * 일시는 AP-12에 따라 Asia/Seoul 오프셋을 포함해 내려준다 — FormDetailResponse.rcptBgngDt와
 * 같은 모양이라 화면이 두 경로에서 온 값을 같은 파서로 읽는다.
 */
public record RecruitmentScheduleResponse(
        Long academicProgramId,
        Long formId,
        OffsetDateTime rcptBgngDt,
        OffsetDateTime rcptEndDt,
        FormReceiptStatus receiptStatus) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static RecruitmentScheduleResponse of(
            Long academicProgramId, FormEntity form, FormReceiptStatus receiptStatus) {
        return new RecruitmentScheduleResponse(
                academicProgramId,
                form.getId(),
                toOffsetDateTime(form.getReceiptBeginAt()),
                toOffsetDateTime(form.getReceiptEndAt()),
                receiptStatus);
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
