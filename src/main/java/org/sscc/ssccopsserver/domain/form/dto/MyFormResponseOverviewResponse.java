package org.sscc.ssccopsserver.domain.form.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;

/*
 * 폼을 가로지르는 내 응답 목록 항목 (ssccops#221 · GET /v1/forms/responses/mine).
 *
 * **MyFormResponseSummaryResponse와 스키마를 나눈다.** 저쪽은 폼 하나 안에서 "내가 몇 건을
 * 냈는가"를 답하므로 폼을 이미 알고 있는 화면이 쓴다. 여기서는 폼을 모르는 채로 시작하므로
 * 폼 제목과 라벨이 항목마다 실려야 하고, 그 둘이 없으면 목록이 "번호만 다른 줄들"이 된다.
 *
 * **응답 내용(rspnsCn)을 싣지 않는다** — 두 목록이 공유하는 규칙이다. 목록이 답하는 것은
 * "무엇을 어떤 상태로 냈는가"이고, 수정요청 사유와 지난 답은 #177이 연 상세가 답한다.
 * 대표 문항의 답(responseTitle) 한 줄만 예외이며 그 근거는 #196에 있다.
 *
 * **폼 라벨이 응답자에게 노출되는 첫 자리다** (2026-09-07 결정). 그전까지 form_lbl은 운영진이
 * 폼을 분류하는 내부 데이터였고 응답자 화면에 나온 적이 없다 — 화면이 라벨로 거를 수 있게 하려면
 * 이름을 내려줘야 한다. 그래서 운영진이 붙이는 라벨은 이제 응답자가 읽는 문구이기도 하다.
 *
 * rspnsSeq(응답 순번)와 sbmsnSeq(제출 회차)는 다른 값이다 — 앞은 이 회원의 몇 번째 응답인가이고
 * 뒤는 그 응답을 몇 번 냈는가다. 수정요청을 받아 다시 낸 응답은 rspnsSeq가 그대로인 채
 * sbmsnSeq만 오른다.
 *
 * 작성 중(DRAFT) 응답도 실린다(sbmsnDt가 null이다). 근거는 리포지토리 주석에 있다.
 *
 * 일시는 AP-12에 따라 Asia/Seoul 오프셋을 포함해 내려준다.
 */
public record MyFormResponseOverviewResponse(
        Long formId,
        String formTtlNm,
        List<FormLabelSummaryResponse> labels,
        Long formRspnsId,
        int rspnsSeq,
        String responseTitle,
        ResponseStatus rspnsSttsCd,
        int sbmsnSeq,
        OffsetDateTime sbmsnDt,
        OffsetDateTime mdfcnDt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    /*
     * 라벨과 대표 문항의 답은 조립하는 자리에서 받는다. 라벨은 폼마다 따로 조회하면 그대로
     * N+1이라 호출부가 한 번에 모아 나눠 주고(FormServiceImpl의 폼 목록과 같은 방식),
     * responseTitle은 SystemFormContract의 선언이 필요해 서비스가 계산한다 (#196).
     */
    public static MyFormResponseOverviewResponse of(
            FormResponseHistoryEntity response,
            List<FormLabelSummaryResponse> labels,
            String responseTitle) {
        return new MyFormResponseOverviewResponse(
                response.getForm().getId(),
                response.getForm().getTitle(),
                labels,
                response.getId(),
                response.getResponseSequence(),
                responseTitle,
                response.getStatus(),
                response.getSubmissionSequence(),
                toOffsetDateTime(response.getSubmittedAt()),
                toOffsetDateTime(response.getUpdatedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
