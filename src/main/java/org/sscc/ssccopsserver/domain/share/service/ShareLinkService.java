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
 *
 * ## 엔드포인트는 대상 도메인 컨트롤러에 둔다 (ssccops#306에서 확정)
 *
 * ssccops#250이 "대상을 하나 더 붙여 본 뒤 정한다"고 넘긴 판단이다. WORK를 붙여 보고 후보
 * 셋 중 **①(도메인 컨트롤러마다 얇게 둔다)**을 택했다.
 *
 * | 후보 | 판정 |
 * |---|---|
 * | ① 도메인 컨트롤러마다 얇게 | **택함.** 아래 |
 * | ② `ShareController` 하나로 (`/v1/share/{targetType}/{targetId}`) | 기각 |
 * | ③ 공통 부분만 뽑고 컨트롤러는 남긴다 | 기각 — 뽑을 것이 없었다 |
 *
 * **붙여 보니 복사되는 것은 한 줄이고 갈리는 것이 나머지 전부였다.** 세 메서드에서 같은 것은
 * `shareLinkService.issue|findActive|revoke(대상종류, 대상ID, ...)` 호출 한 줄뿐이고,
 * 경로·요구 권한(`@RequireAuthority`)·404를 끊는 조회는 대상마다 다르다. ③이 뽑아낼 수 있는
 * 것이 그 한 줄이라 추상화가 원본보다 길어진다.
 *
 * **②를 기각한 이유는 복사가 아까워서가 아니다.** 대상마다 다른 권한과 404 판정을
 * `ShareController` 안에서 분기하면 **공유 도메인이 모든 대상 도메인의 인가 규칙을 알게 된다** —
 * 그것은 `SharePreviewProvider`를 만든 이유(공유 도메인은 대상이 무엇인지 모른다)와 정면으로
 * 부딪히고, 이 저장소가 같은 판단을 이미 두 번 한 자리다. 게다가 인가 규칙 두 번째 벌이
 * 생기는데, 그것은 이 인터페이스가 인가를 하지 않기로 한 바로 그 이유다.
 *
 * **대가는 대상이 늘 때마다 얇은 메서드 셋이 는다는 것**이며, 그 셋은 서로 다른 이유로 다르게
 * 생겼으므로 중복이 아니라 반복이다. 다섯 번째 대상에서도 갈리는 부분이 그대로면 그때 다시 본다.
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
