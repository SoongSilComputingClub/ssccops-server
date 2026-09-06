package org.sscc.ssccopsserver.domain.share.service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.share.code.ShareTargetType;

/*
 * 등록된 SharePreviewProvider를 대상 종류로 찾아 주는 자리 (ssccops#200).
 *
 * `SystemFormApprovalHooks`(#150)를 그대로 따른다 — 호출부가 List를 주입받아 매번
 * stream().filter(...)로 고르면 "같은 대상에 제공자가 둘이면 어떻게 되는가"의 답이 호출부마다
 * 갈린다(먼저 나온 것을 쓰거나 조용히 하나만 돌거나). 맵으로 굳히면서 겹침을 **기동 시점에**
 * 터뜨린다 — 두 도메인이 같은 대상의 미리보기를 만드는 것은 런타임에 골라야 할 선택지가
 * 아니라 설계가 어긋난 상태다.
 */
@Component
public class SharePreviewProviders {

    private final Map<ShareTargetType, SharePreviewProvider> providersByTargetType;

    public SharePreviewProviders(List<SharePreviewProvider> providers) {
        Map<ShareTargetType, SharePreviewProvider> registry = new EnumMap<>(ShareTargetType.class);
        for (SharePreviewProvider provider : providers) {
            SharePreviewProvider previous = registry.put(provider.targetType(), provider);
            if (previous != null) {
                throw new IllegalStateException(
                        "같은 대상 종류에 미리보기 제공자가 둘 등록됐습니다: " + provider.targetType());
            }
        }
        this.providersByTargetType = Map.copyOf(registry);
    }

    public Optional<SharePreviewProvider> find(ShareTargetType targetType) {
        return Optional.ofNullable(providersByTargetType.get(targetType));
    }
}
