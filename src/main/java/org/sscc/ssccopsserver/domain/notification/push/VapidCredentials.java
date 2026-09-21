package org.sscc.ssccopsserver.domain.notification.push;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

/*
 * RFC 8292 — VAPID 자격 (ssccops#446 · ADR-0045).
 *
 * 서버를 식별하는 P-256 키 쌍 하나다. 공개키는 브라우저가 구독할 때 `applicationServerKey`로
 * 넣고(`GET /v1/push/config`), 개인키로는 요청마다 짧은 JWT(ES256)를 서명해 푸시 서비스에
 * «이 구독의 주인이 보낸다»를 증명한다. **키를 바꾸면 그 공개키로 만든 구독 전부가 무효다** —
 * 브라우저가 새 키로 다시 구독해야 한다(ADR-0045가 감수한 대가).
 *
 * 키 형식은 `npx web-push generate-vapid-keys`의 출력 그대로다 — 공개키 base64url 65바이트(비압축
 * 점) · 개인키 base64url 32바이트(스칼라). 형식이 맞지 않으면 **부팅에서 실패한다**
 * (`AppPublicBaseUrl`과 같은 판단 — 틀린 키는 몇 시간 뒤 «전 구독 401»로만 드러난다).
 * 다만 **키가 비어 있는 것은 정당하다**(아직 발급하지 않은 배포 · 기여자 로컬 · test) —
 * 그때는 `WebPushSenderConfig`가 이 클래스를 만들지 않고 Noop 발송기를 세운다.
 *
 * `subject`는 `mailto:` 주소다 — 푸시 서비스 운영자가 문제가 있을 때 연락할 곳(RFC 8292 §2.1).
 */
final class VapidCredentials {

    /*
     * JWT 유효 기간. RFC 8292는 24시간 이하를 요구하고 대부분의 푸시 서비스가 그 이상을 거절한다.
     * 12시간으로 잡아 시계가 조금 어긋난 서버에서도 상한을 넘지 않게 한다. 요청마다 새로 서명하므로
     * 캐시하지 않는다 — 발송 빈도(하루 수십 건)에 ECDSA 서명 한 번은 아무것도 아니다.
     */
    private static final Duration TOKEN_LIFETIME = Duration.ofHours(12);

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final String JWT_HEADER =
            URL_ENCODER.encodeToString(ascii("{\"typ\":\"JWT\",\"alg\":\"ES256\"}"));
    private static final int COORDINATE_LENGTH = 32;

    private final String publicKeyBase64Url;
    private final PrivateKey privateKey;
    private final String subject;

    VapidCredentials(String publicKeyBase64Url, String privateKeyBase64Url, String subject) {
        PublicKey publicKey = P256.publicKeyOf(publicKeyBase64Url);
        this.publicKeyBase64Url = URL_ENCODER.encodeToString(P256.uncompressedBytes(publicKey));
        this.privateKey = P256.privateKeyOf(privateKeyBase64Url);
        if (subject == null || !(subject.startsWith("mailto:") || subject.startsWith("https://"))) {
            throw new IllegalArgumentException(
                    "VAPID subject는 mailto: 또는 https:// 로 시작해야 한다 (RFC 8292 §2.1)");
        }
        this.subject = subject;
    }

    /** 브라우저에 내줄 공개키 — 정규화된 base64url(패딩 없음) */
    String publicKey() {
        return publicKeyBase64Url;
    }

    /**
     * 푸시 서비스 origin(`https://fcm.googleapis.com` 등)을 aud로 하는 서명된 JWT.
     *
     * @param audience endpoint의 scheme + host — 푸시 서비스가 자기 origin과 대조한다
     * @param now 발급 시각(exp 계산)
     */
    String signedToken(String audience, Instant now) {
        String claims =
                "{\"aud\":\""
                        + audience
                        + "\",\"exp\":"
                        + now.plus(TOKEN_LIFETIME).getEpochSecond()
                        + ",\"sub\":\""
                        + subject
                        + "\"}";
        String signingInput = JWT_HEADER + "." + URL_ENCODER.encodeToString(ascii(claims));
        return signingInput + "." + URL_ENCODER.encodeToString(sign(ascii(signingInput)));
    }

    /** `Authorization: vapid t=<jwt>, k=<공개키>` (RFC 8292 §3) */
    String authorizationHeader(String audience, Instant now) {
        return "vapid t=" + signedToken(audience, now) + ", k=" + publicKeyBase64Url;
    }

    /*
     * ES256 서명. JDK의 SHA256withECDSA는 DER(SEQUENCE{r, s})를 내는데 JWS는 r||s 64바이트를
     * 요구한다(RFC 7518 §3.4) — 여기서 바꾼다. JDK 17에는 `SHA256withECDSAinP1363Format`도 있지만
     * 프로바이더에 따라 없을 수 있어 DER를 직접 푼다(형식이 단순하다).
     */
    private byte[] sign(byte[] input) {
        try {
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(privateKey);
            signature.update(input);
            return derToRaw(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("VAPID JWT를 서명할 수 없다", e);
        }
    }

    static byte[] derToRaw(byte[] der) {
        // SEQUENCE(0x30) len INTEGER(0x02) rLen r INTEGER(0x02) sLen s
        int offset = 2;
        if ((der[1] & 0x80) != 0) {
            offset += der[1] & 0x7f;
        }
        int rLength = der[offset + 1];
        byte[] rBytes = Arrays.copyOfRange(der, offset + 2, offset + 2 + rLength);
        offset += 2 + rLength;
        int sLength = der[offset + 1];
        byte[] sBytes = Arrays.copyOfRange(der, offset + 2, offset + 2 + sLength);

        byte[] raw = new byte[COORDINATE_LENGTH * 2];
        copyPadded(new BigInteger(1, rBytes), raw, 0);
        copyPadded(new BigInteger(1, sBytes), raw, COORDINATE_LENGTH);
        return raw;
    }

    private static void copyPadded(BigInteger value, byte[] target, int offset) {
        byte[] bytes = value.toByteArray();
        int start = bytes.length > COORDINATE_LENGTH ? bytes.length - COORDINATE_LENGTH : 0;
        int length = bytes.length - start;
        System.arraycopy(bytes, start, target, offset + (COORDINATE_LENGTH - length), length);
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
