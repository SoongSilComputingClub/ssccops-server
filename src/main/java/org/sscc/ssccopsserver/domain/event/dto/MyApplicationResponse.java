package org.sscc.ssccopsserver.domain.event.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.event.code.ApplicationStatus;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;

/*
 * 내 신청 목록 항목 (ssccops#145 · GET /v1/events/my-applications). 필드명은 공개 앱과 합의된
 * 계약이다.
 *
 * **운영자용 응답(EventSummaryResponse · FormResponseSummaryResponse)과 스키마를 나눈다**
 * (PublicEventSummaryResponse · MyFormResponseSummaryResponse 선례). 신청자에게 필요한 것은
 * "내가 어느 행사에 신청했고 그것이 지금 어떤 상태인가"뿐이라, 여기 **없는** 것이 있는 것만큼
 * 중요하다:
 *   eventSttsCd(행사 저장 상태) · mtxtCn(본문) · confirmedCount · ptcpLmtCnt · 대기 순번 ·
 *   rspnsCn(내 답 내용) · 다른 신청자에 관한 어떤 것도.
 *
 * **대기 순번을 싣지 않는 것은 결정이다**(wave2 D5) — 순번은 신청자에게 비공개다. 명단 조회
 * (#158)가 운영 화면에만 신청 순서를 보여주는 것과 짝이 맞는다.
 *
 * 행사 제목·분류명·일시·장소는 행사에서 조인해 온다(복사하지 않는다) — 행사 정보가 바뀌면
 * 내 신청 목록도 함께 바뀌는 것이 맞다.
 *
 * eventPtcpId는 명단에 오른 신청에만 있고(수동 등록이 아니라 내 신청이므로 formRspnsId는 언제나
 * 있다), submittedAt은 DRAFT가 목록에서 빠지므로 실제로는 언제나 값이 있다 — 타입을 nullable로
 * 두는 것은 계약이 그렇게 합의됐기 때문이고, 응답 상태 어휘가 넓어져도 이 자리가 깨지지 않는다.
 *
 * **formId는 응답 화면의 주소를 만들기 위해서만 싣는다** (#340). 공개 앱이 "내가 낸 것"을 보여주는
 * 주소는 /f/{formId}/mine/{formRspnsId}라 formRspnsId만으로는 어디로 가야 하는지 알 수 없었다.
 * 응답은 언제나 폼에 딸리므로 null이 아니다. 폼의 제목·문항·접수 기간은 여기 없다 — 그것은 폼
 * 조회(GET /v1/forms/{formId}/public)가 답할 일이고, 내 신청 목록이 폼 요약을 복사하기 시작하면
 * 두 응답이 서로 다른 폼을 말하는 날이 온다.
 *
 * 일시는 AP-12에 따라 Asia/Seoul 오프셋을 포함해 내려준다.
 */
public record MyApplicationResponse(
        Long eventId,
        String eventTtl,
        String eventClsfNm,
        OffsetDateTime eventBgngDt,
        OffsetDateTime eventEndDt,
        String plcNm,
        ApplicationStatus applicationStatus,
        Long formId,
        Long formRspnsId,
        Long eventPtcpId,
        OffsetDateTime submittedAt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static MyApplicationResponse of(
            EventEntity event,
            FormResponseHistoryEntity response,
            ApplicationStatus applicationStatus,
            Long eventParticipantId) {
        return new MyApplicationResponse(
                event.getId(),
                event.getTitle(),
                event.getClassification().getName(),
                toOffsetDateTime(event.getBeginAt()),
                toOffsetDateTime(event.getEndAt()),
                event.getPlaceName(),
                applicationStatus,
                event.getForm().getId(),
                response.getId(),
                eventParticipantId,
                toOffsetDateTime(response.getSubmittedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
