package org.sscc.ssccopsserver.domain.share.service;

import java.util.Optional;

import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.share.code.ShareTargetType;
import org.sscc.ssccopsserver.domain.share.dto.PublicSharePreviewResponse;
import org.sscc.ssccopsserver.domain.share.dto.ShareLinkResponse;

/*
 * 공유 링크 발급·폐기·조회 (ssccops#200 · ADR-0016).
 *
 * **인가는 여기서 하지 않는다.** "이 하위 업무를 공유할 수 있는가"는 그 자원을 볼 수 있는가와
 * 같은 질문이고, 그 판정은 대상을 소유한 도메인의 컨트롤러가 `@RequireAuthority`로 이미 하고
 * 있다 — 여기에 대상별 권한 표를 만들면 그것이 곧 인가 규칙 두 번째 벌이 된다(`domain/file`이
 * 접근 제어를 올려받지 않은 것과 같은 이유).
 */
public interface ShareLinkService {

    /*
     * 대상의 공유 링크를 발급한다. **멱등이다** — 살아 있는 링크가 있으면 그것을 그대로
     * 돌려준다. 누를 때마다 새 토큰을 만들면 만료가 없으므로(ADR-0016) 죽지 않는 링크가 쌓이고,
     * 화면이 그중 무엇을 보여줄지에 답이 없다.
     */
    ShareLinkResponse issue(ShareTargetType targetType, Long targetId, MemberEntity issuer);

    /*
     * 대상의 살아 있는 링크. 공유한 적이 없거나 폐기했으면 빈 Optional이며, 화면은 그것으로
     * "공유하기"와 "공유 중지" 중 무엇을 그릴지 정한다.
     */
    Optional<ShareLinkResponse> findActive(ShareTargetType targetType, Long targetId);

    /*
     * 폐기. 살아 있는 링크가 없어도 조용히 지나간다 — 결과("그 링크로는 아무것도 열리지
     * 않는다")가 같은데 두 번째 요청만 오류로 만들 이유가 없다.
     */
    void revoke(ShareTargetType targetType, Long targetId);

    /*
     * 토큰으로 미리보기를 만든다(익명 경로가 부른다). 없는 토큰·폐기된 토큰·대상이 사라진
     * 토큰은 **전부 같은 404**다 — 나누면 어느 토큰이 한때 존재했는지가 드러난다.
     */
    PublicSharePreviewResponse preview(String token);
}
