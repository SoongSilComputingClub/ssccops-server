package org.sscc.ssccopsserver.domain.form.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
import org.sscc.ssccopsserver.domain.form.entity.ResponseContent;

/*
 * 응답 단건 상세 (#37 · GET /v1/forms/{formId}/responses/{formRspnsId}).
 *
 * 목록 항목에 응답 내용(rspnsCn)·응답자의 기수·학년·연락처·인접 응답 식별자를 더한 것이다.
 *
 * prevFormRspnsId / nextFormRspnsId는 상세 화면의 '이전 · 다음' 이동을 위한 값이다
 * (ssccops-web #13이 이미 이 이름으로 소비하고 있다). 웹이 목록을 들고 다니며 계산하는 방법도
 * 있었지만, 그러면 상세를 URL로 바로 열었을 때(새 탭·북마크·알림 링크) 이동 버튼이 죽는다 —
 * 목록을 거쳐 들어왔는지에 따라 화면 기능이 달라지는 것을 피한다.
 *
 * 인접 응답은 **목록의 기본 조회와 같은 순서·같은 범위**에서 고른다. 즉 DRAFT는 이웃이 되지
 * 않으며(심사 대상이 아니다), 정렬도 목록과 같은 '제출 일시 내림차순'이다. 목록에서 본 순서와
 * 이동 순서가 어긋나면 '이전'을 눌렀는데 방금 본 응답이 나오지 않는다.
 *
 * 목록에 없는 응답(DRAFT)을 직접 열면 두 값은 모두 null이다 — 이웃을 만들어 주면 심사 목록에서
 * 빠져 있던 응답이 이동만으로 심사 흐름 안에 들어온다.
 *
 * 일시는 AP-12에 따라 Asia/Seoul 오프셋을 포함해 내려준다.
 */
public record FormResponseDetailResponse(
        Long formRspnsId,
        ResponseStatus rspnsSttsCd,
        OffsetDateTime sbmsnDt,
        ResponseMemberDetail member,
        ResponseContent rspnsCn,
        Long prevFormRspnsId,
        Long nextFormRspnsId) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static FormResponseDetailResponse of(
            FormResponseHistoryEntity response, Long previousId, Long nextId) {
        return new FormResponseDetailResponse(
                response.getId(),
                response.getStatus(),
                toOffsetDateTime(response.getSubmittedAt()),
                ResponseMemberDetail.from(response.getMember()),
                response.getContent(),
                previousId,
                nextId);
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
