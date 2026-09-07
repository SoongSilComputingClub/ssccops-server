package org.sscc.ssccopsserver.domain.share.dto;

import org.sscc.ssccopsserver.domain.share.code.ShareTargetType;
import org.sscc.ssccopsserver.domain.share.service.SharePreview;

/*
 * 익명 미리보기 응답 (ssccops#200). 메신저 크롤러가 카드를 만들 때 받는 전부다.
 *
 * **실리는 것은 제목·요약과 대상 좌표뿐이다.** 상태·진행률·마감일은 담지 않는다 — 카드가 한 번
 * 굳으므로 시간에 따라 변하는 값을 실으면 마감된 뒤에도 "진행 중"이라 말하는 카드가 남는다
 * (ssccops#194 제약 ②). 담을 자리를 만들지 않는 것이 담지 않기로 한 결정을 지키는 방법이다.
 *
 * 대상 좌표(`trgtSeCd`·`trgtId`)를 싣는 것은 **사람이 링크를 눌렀을 때 갈 곳**을 웹이 알아야
 * 하기 때문이다. 그것을 알아도 내용은 볼 수 없다 — 상세는 종전대로 로그인과 권한 검사를
 * 지난다(ADR-0016: 토큰은 미리보기 권한까지다).
 */
public record PublicSharePreviewResponse(
        ShareTargetType trgtSeCd, Long trgtId, String title, String summary) {

    public static PublicSharePreviewResponse of(
            ShareTargetType targetType, Long targetId, SharePreview preview) {
        return new PublicSharePreviewResponse(
                targetType, targetId, preview.title(), preview.summary());
    }
}
