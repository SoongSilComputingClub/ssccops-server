package org.sscc.ssccopsserver.domain.form.dto;

import java.util.Map;

import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;

/*
 * 폼 상세의 응답 요약 (#37 · FormDetailResponse.responseSummary).
 *
 * 폼 상세 화면(ssccops #55 AC)이 전체 · 제출 · 승인 · 반려 네 건수를 한 상자에 보여준다.
 * 그동안 서버가 responseCount 한 숫자만 내려주는 바람에 웹(entities/form/api/forms.ts)은
 * res.responseSummary를 찾지 못하고 조용히 0을 그리고 있었다 — 터지지 않고 틀리는 종류라
 * 화면만 보고는 알 수 없었다.
 *
 * **DRAFT는 어디에도 들어가지 않는다.** #36이 정한 "작성 중 응답은 집계·목록에서 뺀다"와 같은
 * 기준이며, total도 제출 이상 세 상태의 합이다 — 상세의 total과 목록의 responseCount가 갈리면
 * 같은 폼이 화면마다 다른 접수 건수를 갖게 된다. 작성 중 응답의 수가 필요해지면 그때 별도
 * 필드로 더하지, 여기 total을 부풀리지 않는다.
 *
 * 별도 집계 질의를 두지 않는다. FormResponseCount 하나가 (폼, 상태)별로 세어 오고 여기서는
 * 그 결과를 접기만 한다 — 폼 목록은 그 결과를 합으로, 상세는 그대로 쓴다.
 */
public record FormResponseStatusSummary(long total, long submitted, long accepted, long rejected) {

    private static final FormResponseStatusSummary EMPTY =
            new FormResponseStatusSummary(0L, 0L, 0L, 0L);

    /** 응답이 한 건도 없는 폼. GROUP BY 결과에 나오지 않는 것과 0건은 같은 뜻이다 */
    public static FormResponseStatusSummary empty() {
        return EMPTY;
    }

    /*
     * 상태별 건수 맵을 요약으로 접는다. 맵에 DRAFT가 섞여 들어와도 세 상태만 읽으므로 결과는
     * 달라지지 않지만, 애초에 그 상태를 세지 않는 것은 질의 쪽 책임이다
     * (FormServiceImpl.SUBMITTED_OR_LATER).
     */
    public static FormResponseStatusSummary from(Map<ResponseStatus, Long> countByStatus) {
        long submitted = countByStatus.getOrDefault(ResponseStatus.SUBMITTED, 0L);
        long accepted = countByStatus.getOrDefault(ResponseStatus.ACCEPTED, 0L);
        long rejected = countByStatus.getOrDefault(ResponseStatus.REJECTED, 0L);
        return new FormResponseStatusSummary(
                submitted + accepted + rejected, submitted, accepted, rejected);
    }
}
