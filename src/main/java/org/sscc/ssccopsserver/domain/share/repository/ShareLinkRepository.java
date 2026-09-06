package org.sscc.ssccopsserver.domain.share.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.share.code.ShareTargetType;
import org.sscc.ssccopsserver.domain.share.entity.ShareLinkEntity;

/*
 * 공유 링크 조회 (ssccops#200).
 */
public interface ShareLinkRepository extends JpaRepository<ShareLinkEntity, Long> {

    /*
     * 토큰으로 찾는다. 폐기 여부는 조건에 넣지 않고 엔티티에 물어본다(`isActive`) — 폐기된
     * 토큰과 없는 토큰이 같은 404로 나가야 하므로 어느 쪽이든 결과가 같고, 조건에 넣으면
     * "폐기된 것을 찾았다"를 서비스가 구별할 방법이 아예 없어져 로그도 남길 수 없다.
     */
    Optional<ShareLinkEntity> findByToken(String token);

    /*
     * 대상의 살아 있는 링크. 발급이 멱등이려면 먼저 이것을 찾아야 한다 — 누를 때마다 새 토큰을
     * 만들면 한 하위 업무에 죽지 않는 링크가 쌓이고, 그중 무엇을 화면에 보여줄지가 답이 없다.
     */
    Optional<ShareLinkEntity> findByTargetTypeAndTargetIdAndRevokedAtIsNull(
            ShareTargetType targetType, Long targetId);
}
