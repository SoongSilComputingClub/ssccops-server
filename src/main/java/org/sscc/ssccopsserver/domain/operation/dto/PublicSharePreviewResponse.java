package org.sscc.ssccopsserver.domain.operation.dto;

import org.sscc.ssccopsserver.domain.operation.entity.OperationEntity;
import org.sscc.ssccopsserver.domain.operation.entity.OperationType;

/*
 * 익명 미리보기 (ssccops#200 · ADR-0016 · GET /public/v1/share/{token}).
 *
 * **담는 것은 제목과 종류뿐이다. 이 좁음이 실수가 아니라 결정이다.**
 *
 * 메신저는 OG를 캐싱하고 갱신하지 않으므로 카드는 **처음 공유될 때 한 번 굳는다.** 상태·
 * 진행률·마감일을 넣으면 몇 주 뒤에도 그때 값을 말하는 카드가 방에 남는다 — 없는 것보다
 * 나쁘다. 이미 있는 행사 OG가 같은 선을 지키고 있다(기간·장소·본문 요약만 담고 모집 배지는
 * 넣지 않았다).
 *
 * **담당자·등록자도 싣지 않는다.** 사람 이름은 익명에게 나갈 이유가 없고, 여기는
 * /public/v1이라 나가는 값 하나하나가 permitAll의 표면이다.
 *
 * 종류(operTypeCd)를 싣는 것은 그 값이 운영 건의 성격이라 바뀌지 않기 때문이다 — 업무가
 * 회의가 되는 전이는 없다.
 */
public record PublicSharePreviewResponse(String operTtl, OperationType operTypeCd) {

    public static PublicSharePreviewResponse from(OperationEntity operation) {
        return new PublicSharePreviewResponse(operation.getTitle(), operation.getOperationType());
    }
}
