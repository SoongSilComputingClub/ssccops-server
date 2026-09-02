package org.sscc.ssccopsserver.domain.form.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;

/*
 * 응답 목록 항목 (#37 · GET /v1/forms/{formId}/responses).
 *
 * 응답 내용(rspnsCn)을 싣지 않는다. 목록 표가 그리는 것은 응답자·제출 일시·상태뿐인데,
 * 답 전체를 함께 실으면 모집 폼 한 회차의 목록 응답이 문항 수 × 응답 수만큼 커진다.
 * 답이 필요하면 상세를 부른다.
 *
 * sbmsnDt는 DRAFT인 응답에서 null이다 (ssccops #64 · 제출하지 않은 응답은 제출 일시를 가질 수
 * 없다). 그 응답은 statusCode=DRAFT를 명시했을 때만 목록에 나온다.
 *
 * 상태 변경(PATCH .../status)의 응답 본문도 이 모양을 쓴다 — 웹은 변경 후 재조회로 화면을
 * 맞추므로 본문을 읽지 않지만, 응답 없는 200을 돌려주면 ApiResponse 봉투만 남아 무엇이 바뀌었는지
 * 확인할 방법이 사라진다.
 *
 * rspnsSeq(응답 순번)는 #143에서 더했다. 다중 응답을 허용하는 폼에서는 같은 회원의 응답이 여러
 * 행으로 나오는데, 목록에 그 값이 없으면 운영자는 이름이 같은 두 줄을 구별할 방법이 제출 일시밖에
 * 없다. **제출 회차(sbmsnSeq)와 다른 값이다** — 이쪽은 몇 번째 응답인가이고 그쪽은 그 응답을 몇 번
 * 냈는가라, 목록에는 앞의 것만 싣는다(회차는 상세가 이력과 함께 보여준다).
 *
 * responseTitle(대표 문항의 답)은 #196에서 더했다. 기획안 검토 목록이 회원명 옆에 "1번째 · 2번째"만
 * 띄워 학술국장이 어느 기획안인지 열어 보기 전에는 알 수 없었다(ssccops-web#204). 어느 문항이
 * 그 폼의 대표값인지는 SystemFormContract가 선언하고 이 DTO는 정해진 값을 싣기만 한다 —
 * 목록에 응답 내용 전체를 싣는 것과는 다르다. **값이 없으면 null이며 서버가 대체값을 만들지
 * 않는다**(선언이 없는 평범한 폼, 대표 문항이 지워진 폼, 제출자가 비워 둔 답 전부 null이다).
 * rspnsSeq를 대체하지 않고 함께 실리는 값이다 — 같은 사람이 같은 이름으로 두 번 낼 수 있어
 * 그 둘을 가르는 것은 여전히 순번뿐이다.
 *
 * 일시는 AP-12에 따라 Asia/Seoul 오프셋을 포함해 내려준다.
 */
public record FormResponseSummaryResponse(
        Long formRspnsId,
        int rspnsSeq,
        String responseTitle,
        ResponseStatus rspnsSttsCd,
        OffsetDateTime sbmsnDt,
        ResponseMemberSummary member) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    /*
     * 대표 문항의 답을 인자로 받는다 (#196). DTO가 SystemFormContract를 들고 직접 꺼내지 않는 것은
     * 조립하는 자리가 그 선언을 이미 읽고 있어서다 — 두 곳에서 꺼내면 목록과 검토 응답이 서로 다른
     * 규칙으로 제목을 고르는 자리가 생긴다(FormResponseServiceImpl.responseTitleOf 한 곳이다).
     */
    public static FormResponseSummaryResponse of(
            FormResponseHistoryEntity response, String responseTitle) {
        return new FormResponseSummaryResponse(
                response.getId(),
                response.getResponseSequence(),
                responseTitle,
                response.getStatus(),
                toOffsetDateTime(response.getSubmittedAt()),
                ResponseMemberSummary.from(response.getMember()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
