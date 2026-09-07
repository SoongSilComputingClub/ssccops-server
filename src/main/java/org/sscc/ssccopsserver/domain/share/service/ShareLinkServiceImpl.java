package org.sscc.ssccopsserver.domain.share.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.share.code.ShareTargetType;
import org.sscc.ssccopsserver.domain.share.code.error.ShareErrorCode;
import org.sscc.ssccopsserver.domain.share.dto.PublicSharePreviewResponse;
import org.sscc.ssccopsserver.domain.share.dto.ShareLinkResponse;
import org.sscc.ssccopsserver.domain.share.entity.ShareLinkEntity;
import org.sscc.ssccopsserver.domain.share.repository.ShareLinkRepository;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

/*
 * 공유 링크의 구현 (ssccops#200 · ADR-0016).
 *
 * 이 클래스가 지키는 것은 둘이다 — **토큰은 추측할 수 없어야 하고, 미리보기를 내주는 판정은
 * 한 자리를 지나야 한다.**
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShareLinkServiceImpl implements ShareLinkService {

    /*
     * 토큰 바이트 수. 32바이트(256비트)를 URL-safe Base64로 적으면 43자다 — 열거가 불가능한
     * 크기이고 컬럼 길이(64) 안에 들어간다. 짧게 줄이지 말 것: 이 방식을 고른 이유 자체가
     * "식별자를 훑는 공격면을 없앤다"이므로(ADR-0016) 토큰이 추측 가능해지면 공개 메타 API를
     * 기각한 근거가 통째로 사라진다.
     */
    private static final int TOKEN_BYTES = 32;

    /*
     * SecureRandom이어야 한다. Random·UUID.randomUUID()의 하위 비트는 예측 가능하거나
     * 엔트로피가 부족해, 토큰이 곧 접근 권한인 자리에 쓸 수 없다.
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final ShareLinkRepository shareLinkRepository;
    private final SharePreviewProviders sharePreviewProviders;
    private final Clock clock;

    @Override
    @Transactional
    public ShareLinkResponse issue(ShareTargetType targetType, Long targetId, MemberEntity issuer) {
        return shareLinkRepository
                .findByTargetTypeAndTargetIdAndRevokedAtIsNull(targetType, targetId)
                .map(ShareLinkResponse::of)
                .orElseGet(
                        () ->
                                ShareLinkResponse.of(
                                        shareLinkRepository.save(
                                                ShareLinkEntity.issue(
                                                        newToken(),
                                                        targetType,
                                                        targetId,
                                                        issuer))));
    }

    @Override
    public Optional<ShareLinkResponse> findActive(ShareTargetType targetType, Long targetId) {
        return shareLinkRepository
                .findByTargetTypeAndTargetIdAndRevokedAtIsNull(targetType, targetId)
                .map(ShareLinkResponse::of);
    }

    @Override
    @Transactional
    public void revoke(ShareTargetType targetType, Long targetId) {
        Instant now = Instant.now(clock);
        shareLinkRepository
                .findByTargetTypeAndTargetIdAndRevokedAtIsNull(targetType, targetId)
                .ifPresent(link -> link.revoke(now));
    }

    /*
     * 토큰 → 미리보기.
     *
     * **네 갈래가 전부 같은 404로 수렴한다** — 없는 토큰 · 폐기된 토큰 · 제공자가 없는 대상 종류
     * (이쪽만 500이다, 요청의 잘못이 아니므로) · 대상이 지워진 경우. 코드를 나누면 어느 토큰이
     * 한때 존재했는지가 드러나 토큰을 무작위로 둔 이유가 절반 무효가 된다.
     */
    @Override
    public PublicSharePreviewResponse preview(String token) {
        ShareLinkEntity link =
                shareLinkRepository
                        .findByToken(token)
                        .filter(ShareLinkEntity::isActive)
                        .orElseThrow(
                                () -> new GeneralException(ShareErrorCode.SHARE_LINK_NOT_FOUND));

        SharePreviewProvider provider =
                sharePreviewProviders
                        .find(link.getTargetType())
                        .orElseThrow(
                                () ->
                                        new GeneralException(
                                                ShareErrorCode.SHARE_PREVIEW_PROVIDER_MISSING));

        return provider.preview(link.getTargetId())
                .map(
                        preview ->
                                PublicSharePreviewResponse.of(
                                        link.getTargetType(), link.getTargetId(), preview))
                .orElseThrow(() -> new GeneralException(ShareErrorCode.SHARE_LINK_NOT_FOUND));
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }
}
