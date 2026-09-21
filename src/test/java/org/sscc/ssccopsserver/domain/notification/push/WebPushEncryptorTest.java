package org.sscc.ssccopsserver.domain.notification.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

/*
 * RFC 8291 부록 A의 값과 바이트 단위로 대조한다 (ssccops#446).
 *
 * 라이브러리 대신 직접 구현한 암호화가 «맞다»의 근거는 이 테스트 하나다 — 서버 임시 키·salt를
 * RFC의 값으로 주입하면 출력이 RFC 5절의 본문과 정확히 같아야 한다. 왕복 테스트(암호화 → 복호화)는
 * 양쪽이 같은 오해를 공유해도 통과하므로, 표준 벡터 대조가 먼저다.
 */
class WebPushEncryptorTest {

    // RFC 8291 Appendix A — 입력
    private static final String PLAINTEXT = "When I grow up, I want to be a watermelon";
    private static final String AS_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
    private static final String AS_PUBLIC =
            "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    private static final String UA_PRIVATE = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94";
    private static final String UA_PUBLIC =
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String SALT = "DGv6ra1nlYgDCS1FRnbzlw";
    private static final String AUTH_SECRET = "BTBZMqHH6r4Tts7J_aSIgg";

    // RFC 8291 §5 — 기대 출력 (헤더 86바이트 + 암호문)
    private static final String EXPECTED_HEADER =
            "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml"
                    + "mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    private static final String EXPECTED_CIPHERTEXT =
            "8pfeW0KbunFT06SuDKoJH9Ql87S1QUrdirN6GcG7sFz1y1sqLgVi1VhjVkHsUoEsbI_0LpXMuGvnzQ";

    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    @Test
    void matchesRfc8291AppendixAByteForByte() {
        WebPushEncryptor encryptor = new WebPushEncryptor();
        KeyPair serverKeyPair =
                new KeyPair(P256.publicKeyOf(AS_PUBLIC), P256.privateKeyOf(AS_PRIVATE));

        byte[] body =
                encryptor.encrypt(
                        PLAINTEXT.getBytes(StandardCharsets.UTF_8),
                        UA_PUBLIC,
                        AUTH_SECRET,
                        serverKeyPair,
                        DECODER.decode(SALT));

        byte[] expected =
                concat(DECODER.decode(EXPECTED_HEADER), DECODER.decode(EXPECTED_CIPHERTEXT));
        assertThat(body).isEqualTo(expected);
        assertThat(body).hasSize(86 + PLAINTEXT.length() + 1 + 16);
    }

    /*
     * 브라우저 쪽(수신자)을 흉내 내 복호화한다 — 운영 경로(임시 키·salt를 서버가 만드는 encrypt)가
     * 부록 A 경로와 같은 코드를 지나는지 본다. 키 파생은 RFC 8291 §3.3·§3.4를 여기서 다시 적었다.
     */
    @Test
    void randomlyKeyedMessageDecryptsOnTheUserAgentSide() throws Exception {
        KeyPair userAgent = P256.generateKeyPair();
        String p256dh =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(P256.uncompressedBytes(userAgent.getPublic()));
        byte[] plaintext = "{\"title\":\"[승인 요청] 부스 배치도\"}".getBytes(StandardCharsets.UTF_8);

        byte[] body = new WebPushEncryptor().encrypt(plaintext, p256dh, AUTH_SECRET);

        assertThat(decrypt(body, userAgent.getPrivate(), p256dh, DECODER.decode(AUTH_SECRET)))
                .isEqualTo(plaintext);
    }

    @Test
    void rejectsPayloadsThatDoNotFitOneRecord() {
        byte[] tooLong = new byte[WebPushEncryptor.RECORD_SIZE];
        assertThatThrownBy(() -> new WebPushEncryptor().encrypt(tooLong, UA_PUBLIC, AUTH_SECRET))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsKeysThatAreNotOnTheCurve() {
        byte[] bogus = new byte[65];
        bogus[0] = 0x04;
        bogus[64] = 0x01;
        String notOnCurve = Base64.getUrlEncoder().withoutPadding().encodeToString(bogus);
        assertThatThrownBy(
                        () ->
                                new WebPushEncryptor()
                                        .encrypt(new byte[] {1}, notOnCurve, AUTH_SECRET))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] decrypt(
            byte[] body, PrivateKey userAgentPrivate, String p256dh, byte[] authSecret)
            throws Exception {
        byte[] salt = Arrays.copyOfRange(body, 0, 16);
        int idLength = body[20];
        byte[] serverPublic = Arrays.copyOfRange(body, 21, 21 + idLength);
        byte[] ciphertext = Arrays.copyOfRange(body, 21 + idLength, body.length);

        PublicKey serverKey = P256.publicKeyOf(serverPublic);
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(userAgentPrivate);
        agreement.doPhase(serverKey, true);
        byte[] ecdhSecret = agreement.generateSecret();

        byte[] keyInfo =
                concat(
                        "WebPush: info\0".getBytes(StandardCharsets.US_ASCII),
                        DECODER.decode(p256dh),
                        serverPublic);
        byte[] ikm = WebPushEncryptor.hkdf(authSecret, ecdhSecret, keyInfo, 32);
        byte[] prk = WebPushEncryptor.hmac(salt, ikm);
        byte[] cek =
                WebPushEncryptor.hkdfExpand(
                        prk,
                        "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII),
                        16);
        byte[] nonce =
                WebPushEncryptor.hkdfExpand(
                        prk, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.DECRYPT_MODE,
                new SecretKeySpec(cek, "AES"),
                new GCMParameterSpec(128, nonce));
        byte[] record = cipher.doFinal(ciphertext);
        assertThat(record[record.length - 1]).isEqualTo((byte) 0x02);
        return Arrays.copyOf(record, record.length - 1);
    }

    private static byte[] concat(byte[]... parts) {
        int length = Arrays.stream(parts).mapToInt(part -> part.length).sum();
        byte[] out = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }
}
