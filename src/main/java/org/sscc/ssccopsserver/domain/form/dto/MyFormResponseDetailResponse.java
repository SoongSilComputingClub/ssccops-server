package org.sscc.ssccopsserver.domain.form.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseReviewHistoryEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
import org.sscc.ssccopsserver.domain.form.entity.ResponseContent;

/*
 * 제출자용 본인 응답 상세 (#177 · GET /v1/forms/{formId}/responses/mine/{formRspnsId}).
 *
 * #141이 검토 처리 이력과 재제출 흐름을 만들었지만 응답자 쪽 화면 경로는 열지 않았다 — 제출자는
 * 왜 수정요청을 받았는지 읽을 수 없었고 자기가 뭘 썼는지 불러올 수도 없어, 재제출은 전체 본문을
 * 처음부터 다시 치는 것으로만 됐다. 이 응답이 그 두 가지(rspnsCn · reviewHistories)를 함께
 * 내려 준다.
 *
 * ── 운영자용 상세(FormResponseDetailResponse)를 그대로 쓰지 않는 이유 ──
 * **prevFormRspnsId · nextFormRspnsId가 남의 응답 식별자이기 때문이다.** 그 두 값은 심사 목록의
 * 이웃을 가리키므로 제출자에게 내려주면 남이 이 폼에 응답했다는 사실과 그 식별자가 그대로 새어
 * 나간다. 회원 정보(ResponseMemberDetail — 학번·학과·등급·상태)도 뺐다. 그 회원은 요청 주체
 * 본인이라 실을 이유가 없고, 응답자용과 운영자용 스키마를 나눠 온 규칙(#35 · #143의 내 응답
 * 목록)을 상세에도 그대로 적용한다 — 한 record를 공유하면 운영자용에 필드가 하나 늘 때마다
 * 응답자 경로로 새어 나갈 것이 함께 는다.
 *
 * ── 이력 항목은 운영자용과 같은 record다 ─────────────────
 * FormResponseReviewHistoryResponse를 그대로 재사용하므로 **처리자_명(prcsMbrNm)이 제출자에게도
 * 보인다** (#177 결정 1). 동아리 내부 결재라 누가 처리했는지가 감출 값이 아니고 — 학술 승인
 * 이력(#139)도 aprvrMbrNm을 싣는다 — 이름을 빼려면 오히려 응답자 전용 이력 record를 하나 더
 * 만들어야 해서 범위가 커진다. 시간순(처리 일시 오름차순)이며 처리가 없으면 빈 배열이다.
 *
 * 제출 이력(SUBMIT)도 함께 실리므로 타임라인이 "제출 → 수정요청 → 재제출 → 승인"으로 읽힌다.
 *
 * ── 학술 승인 미리보기(academicProgramPreview)는 싣지 않는다 ──
 * 그 값은 검토자가 승인을 누르기 전에 파싱 결과를 확인하기 위한 것이다(#150). 제출자에게는
 * 누를 버튼이 없어 지금 쓰는 곳이 없고, 미리 실으면 쓰지 않는 계약이 먼저 굳는다 — 내 응답
 * 목록이 rspnsCn을 미뤘던 것과 같은 판단이며, 필요해지면 그 화면과 함께 더한다.
 *
 * ── 문항 구성(qitemCpstCn)을 함께 싣는다 (ssccops#221) ──
 * 답만으로는 재제출 폼을 그릴 수 없다 — 그 답을 어느 문항에 붙일지를 모르기 때문이다. 그리고
 * 응답자가 문항을 따로 받을 길이 없다: GET /{formId}/public은 findAcceptingForm이 **마감된 폼을
 * 409로 끊는데** 재제출의 실제 쓰임이 마감 뒤에 있고(기획안은 접수를 마감한 뒤 검토한다),
 * GET /forms/system/{sysFormCd}는 시스템 폼 전용이다. 그래서 재제출 API는 그 응답을 받아 주는데
 * 화면이 그것을 그릴 수 없는 상태였다.
 *
 * **마감 409를 푸는 대신 여기에 실었다.** 그쪽을 풀면 "접수 중인 폼을 새로 낸다"는 그 경로의
 * 뜻이 흐려진다 — 재제출은 이미 마감 판정을 타지 않는 예외를 갖고 있으므로(#177) 조회에도 같은
 * 예외가 대칭으로 생기는 편이 맞다. 전용 조회를 새로 열지 않은 것은 이력을 통째로 싣는 것과
 * 같은 이유다: 화면이 상세와 문항을 언제나 함께 그리므로 나누면 두 응답 사이에 폼이 편집됐을 때
 * 답과 문항이 서로 다른 시점을 가리킨다.
 *
 * **새로 새는 것이 없다.** 이 응답은 본인 행만 내려간다(findByIdAndFormAndMember — 남의 것은
 * 없는 응답과 같은 404다). 문항 구성은 그 응답자가 이미 답한 폼의 것이다.
 *
 * rspnsSeq(응답 순번)와 sbmsnSeq(제출 회차)는 **다른 값이다** — 앞은 이 응답자의 몇 번째 응답인가
 * 이고 뒤는 그 응답을 몇 번 냈는가다. 이력의 각 줄이 몇 회차에 대한 처리였는지 읽으려면
 * "지금 몇 회차인가"라는 기준점이 필요해 후자를 함께 싣는다.
 *
 * 일시는 AP-12에 따라 Asia/Seoul 오프셋을 포함해 내려준다.
 */
public record MyFormResponseDetailResponse(
        Long formRspnsId,
        int rspnsSeq,
        ResponseStatus rspnsSttsCd,
        int sbmsnSeq,
        OffsetDateTime sbmsnDt,
        OffsetDateTime mdfcnDt,
        ResponseContent rspnsCn,
        QuestionCompositionContent qitemCpstCn,
        List<FormResponseReviewHistoryResponse> reviewHistories) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static MyFormResponseDetailResponse of(
            FormResponseHistoryEntity response,
            List<FormResponseReviewHistoryEntity> reviewHistories) {
        return new MyFormResponseDetailResponse(
                response.getId(),
                response.getResponseSequence(),
                response.getStatus(),
                response.getSubmissionSequence(),
                toOffsetDateTime(response.getSubmittedAt()),
                toOffsetDateTime(response.getUpdatedAt()),
                response.getContent(),
                response.getForm().getQuestionComposition(),
                reviewHistories.stream().map(FormResponseReviewHistoryResponse::from).toList());
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
