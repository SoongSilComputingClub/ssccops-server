package org.sscc.ssccopsserver.domain.form.repository;

import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;

/*
 * 폼별·상태별 응답 건수 집계 결과 (#32 폼 목록 · #37 폼 상세의 응답 요약).
 *
 * 폼 목록은 폼마다 "응답 N건"을 보여주는데, 폼 수만큼 카운트 쿼리를 날리면 그대로 N+1이 된다
 * (DB-13). 응답 전체가 아니라 개수만 있으면 되므로 한 번의 집계 쿼리로 받아오기 위한 프로젝션이다.
 * SubWorkChecklistProgress와 같은 방식이다.
 *
 * #37에서 상태(status)가 한 축 더 늘었다. 폼 상세가 전체·제출·승인·반려 네 숫자를 보여줘야
 * 하는데, 총합 질의와 상태별 질의를 따로 두면 폼 목록이 폼마다 두 번씩 집계하게 되고 두 결과가
 * 어긋날 여지도 생긴다. 한 질의로 상태별까지 받아 두면 총합은 호출부가 접어 쓰면 된다 —
 * 쿼리 수는 그대로다.
 *
 * 응답이 한 건도 없는 폼은 GROUP BY 결과에 아예 나오지 않는다. 마찬가지로 그 폼에 없는 상태도
 * 행이 나오지 않는다. 두 경우를 0건으로 볼지는 여기가 아니라 호출부가 정한다.
 *
 * 임시저장(DRAFT)을 셀지 말지도 호출부가 정한다 — "접수 건수"로 보여줄 때는 빼야 하고
 * "작성 중 포함"으로 볼 때는 넣어야 해서, 상태를 인자로 받는 질의 쪽에서 갈린다.
 */
public interface FormResponseCount {

    Long getFormId();

    ResponseStatus getStatus();

    long getResponseCount();
}
