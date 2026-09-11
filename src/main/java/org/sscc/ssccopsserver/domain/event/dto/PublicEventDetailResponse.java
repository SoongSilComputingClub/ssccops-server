package org.sscc.ssccopsserver.domain.event.dto;

import java.time.OffsetDateTime;

import org.sscc.ssccopsserver.domain.event.code.EventPhase;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.form.code.FormReceiptStatus;

/*
 * 공개 행사 상세 (ssccops#143 · GET /public/v1/events/{eventId}).
 *
 * 계약대로 공개 목록(PublicEventSummaryResponse)에 mtxtCn·ptcpLmtCnt·confirmedCount 셋을 더한
 * 모양이다. 운영자용 EventDetailResponse와 클래스를 나눈 이유는 목록 쪽 주석에 있다.
 *
 * **참가 인원은 숫자까지만 내린다** (D12). "확정 12명 / 정원 20명"은 신청 여부를 판단하는 데
 * 필요한 사실이지만 누가 확정됐는지는 아니다 — 명단은 참가자 본인들에게도 공개하지 않기로 한
 * 결정이라 익명 경로에 실릴 자리가 없다. ptcpLmtCnt가 null이면 정원 무제한이고, 화면은 그때
 * 확정 인원만 표시한다.
 *
 * **formId는 식별자까지만 내린다** (ssccops-server#165). 신청 버튼을 누른 방문자를 연결 폼으로
 * 보내려면 공개 앱이 어느 폼인지 알아야 한다 — receiptStatus만으로는 "모집 중"까지만 알 수
 * 있고 어디로 가야 하는지는 알 수 없었다. 문항을 실은 것이 아니므로 D12에 어긋나지 않는다:
 * 문항을 받으려면 여전히 인증이 필요한 GET /v1/forms/{formId}/public을 거쳐야 하고, 신청이
 * 회원만 가능하다는 D2도 그대로다. 폼이 없는 공지 성격 행사는 null이다.
 *
 * 목록(PublicEventSummaryResponse)에는 넣지 않았다 — 목록에서 곧장 신청하는 경로가 없다.
 *
 * **mltplRspnsYn(연결 폼의 다중 응답 허용 여부)도 같은 자리에 싣는다** (#340). 공개 앱이 "이미
 * 냈는데 또 낼 수 있는가"를 판단해 신청 버튼을 그리려면 이 플래그가 필요한데, 행사 상세는 익명
 * SSR이라(OG 미리보기 · wave2 D7) 폼을 조회할 수 없고, 로그인한 뒤 폼 전체(문항 포함)를 받아
 * 플래그 하나를 꺼내는 것은 무겁다. 여러 건을 받는지는 접수 기간처럼 공개해도 새는 것이 없는
 * 값이다 — 이미 receiptStatus(연결 폼의 접수 상태)가 같은 방식으로 실려 나간다. 폼이 없는
 * 공지 성격 행사는 formId·receiptStatus와 함께 null이다. 폼의 나머지(제목·문항·응답 수)는
 * 여전히 여기 없다.
 *
 * 본문(mtxtCn)은 md 원문 그대로다 — 렌더링·sanitize는 공개 앱의 안전 렌더러 책임이고 원시
 * HTML은 허용하지 않는다(D12). 서버가 여기서 HTML을 벗기지 않는 것은 저장된 것과 서빙되는 것이
 * 갈리면 운영자가 편집기에서 본 문서와 방문자가 보는 문서가 달라지기 때문이다.
 */
public record PublicEventDetailResponse(
        Long eventId,
        String eventClsfCd,
        String eventClsfNm,
        String eventTtl,
        String mtxtCn,
        String thmbUrlAddr,
        EventPhase eventPhase,
        FormReceiptStatus receiptStatus,
        Long formId,
        Boolean mltplRspnsYn,
        OffsetDateTime eventBgngDt,
        OffsetDateTime eventEndDt,
        String plcNm,
        Integer ptcpLmtCnt,
        long confirmedCount) {

    public static PublicEventDetailResponse of(
            EventEntity event,
            EventPhase eventPhase,
            FormReceiptStatus receiptStatus,
            long confirmedCount) {
        return new PublicEventDetailResponse(
                event.getId(),
                event.getClassification().getCode(),
                event.getClassification().getName(),
                event.getTitle(),
                event.getContentMarkdown(),
                event.getThumbnailUrlAddress(),
                eventPhase,
                receiptStatus,
                event.getForm() == null ? null : event.getForm().getId(),
                event.getForm() == null ? null : event.getForm().isMultipleResponseAllowed(),
                PublicEventSummaryResponse.toOffsetDateTime(event.getBeginAt()),
                PublicEventSummaryResponse.toOffsetDateTime(event.getEndAt()),
                event.getPlaceName(),
                event.getParticipantLimitCount(),
                confirmedCount);
    }
}
