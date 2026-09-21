package org.sscc.ssccopsserver.domain.notification.push;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/*
 * RFC 8291 — Web Push 메시지 암호화 (`aes128gcm` · RFC 8188) (ssccops#446 · ADR-0045).
 *
 * 브라우저가 구독할 때 준 P-256 공개키(p256dh)와 인증 비밀(auth)로 **그 브라우저만 풀 수 있는**
 * 본문을 만든다. 푸시 서비스(FCM 엔드포인트 · Mozilla autopush 등)는 지나가기만 하고 내용을 보지
 * 못한다 — 알림 제목에 업무명이 실리므로 이것이 있어야 외부 서비스에 운영 내용이 새지 않는다.
 *
 * 절차(RFC 8291 §3 · 값 이름은 RFC 그대로):
 *   1. 서버가 메시지마다 임시 P-256 키 쌍(as_private · as_public)과 16바이트 salt를 만든다
 *   2. ecdh_secret = ECDH(as_private, ua_public)
 *   3. IKM  = HKDF-Extract+Expand(salt = auth_secret, IKM = ecdh_secret,
 *             info = "WebPush: info\0" || ua_public || as_public, L = 32)
 *   4. PRK  = HKDF-Extract(salt, IKM)
 *      CEK  = HKDF-Expand(PRK, "Content-Encoding: aes128gcm\0", 16)
 *      NONCE = HKDF-Expand(PRK, "Content-Encoding: nonce\0", 12)
 *   5. 본문 = 헤더(salt 16 · rs 4 · idlen 1 · as_public 65) || AES-128-GCM(평문 || 0x02)
 *
 * **레코드는 하나다.** 페이로드가 4KB 이하라(#446 계약 · 푸시 서비스의 상한) rs=4096 한 레코드에
 * 들어가고, 여러 레코드로 쪼개는 코드는 쓸 일이 없어 두지 않았다 — 넘으면 보내지 않고 예외다.
 *
 * **라이브러리(`nl.martijndwars:web-push`)를 쓰지 않은 이유**는 `WebPushSenderConfig` 주석에 있다.
 * 이 클래스는 RFC 8291 부록 A의 중간값·최종 본문과 바이트 단위로 대조된다
 * (`WebPushEncryptorTest` — 임시 키·salt를 주입받는 생성자가 그래서 있다).
 */
final class WebPushEncryptor {

    /** RFC 8188 레코드 크기. 헤더의 rs 필드에 그대로 실린다 */
    static final int RECORD_SIZE = 4096;

    private static final int SALT_LENGTH = 16;
    private static final int CEK_LENGTH = 16;
    private static final int NONCE_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int IKM_LENGTH = 32;
    private static final byte PADDING_DELIMITER_LAST_RECORD = 0x02;

    private static final byte[] KEY_INFO_PREFIX = ascii("WebPush: info\0");
    private static final byte[] CEK_INFO = ascii("Content-Encoding: aes128gcm\0");
    private static final byte[] NONCE_INFO = ascii("Content-Encoding: nonce\0");

    private final SecureRandom random;

    WebPushEncryptor() {
        this(new SecureRandom());
    }

    WebPushEncryptor(SecureRandom random) {
        this.random = random;
    }

    /** 구독의 키로 평문을 암호화한 본문. 메시지마다 새 임시 키·salt */
    byte[] encrypt(byte[] plaintext, String p256dhBase64Url, String authBase64Url) {
        byte[] salt = new byte[SALT_LENGTH];
        random.nextBytes(salt);
        return encrypt(plaintext, p256dhBase64Url, authBase64Url, P256.generateKeyPair(), salt);
    }

    /*
     * 임시 키·salt를 밖에서 받는 버전 — RFC 부록 A 대조 테스트용. 운영 코드는 위의 것을 부른다
     * (같은 키·salt를 두 메시지에 쓰면 GCM nonce가 겹쳐 암호화가 무너진다).
     */
    byte[] encrypt(
            byte[] plaintext,
            String p256dhBase64Url,
            String authBase64Url,
            KeyPair serverKeyPair,
            byte[] salt) {
        if (plaintext.length + 1 + GCM_TAG_BITS / 8 > RECORD_SIZE) {
            throw new IllegalArgumentException(
                    "푸시 페이로드가 한 레코드(" + RECORD_SIZE + "바이트)를 넘는다: " + plaintext.length);
        }
        byte[] userAgentPublic = P256.decode(p256dhBase64Url);
        PublicKey userAgentKey = P256.publicKeyOf(userAgentPublic);
        byte[] authSecret = P256.decode(authBase64Url);
        byte[] serverPublic = P256.uncompressedBytes(serverKeyPair.getPublic());

        try {
            byte[] ecdhSecret = ecdh(serverKeyPair.getPrivate(), userAgentKey);

            byte[] keyInfo = concat(KEY_INFO_PREFIX, userAgentPublic, serverPublic);
            byte[] ikm = hkdf(authSecret, ecdhSecret, keyInfo, IKM_LENGTH);
            byte[] prk = hmac(salt, ikm);
            byte[] cek = hkdfExpand(prk, CEK_INFO, CEK_LENGTH);
            byte[] nonce = hkdfExpand(prk, NONCE_INFO, NONCE_LENGTH);

            byte[] record = concat(plaintext, new byte[] {PADDING_DELIMITER_LAST_RECORD});
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(cek, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(record);

            return concat(header(salt, serverPublic), ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Web Push 본문을 암호화할 수 없다", e);
        }
    }

    /** RFC 8188 §2.1 — salt(16) · rs(uint32 BE) · idlen(1) · keyid(= as_public 65) */
    private static byte[] header(byte[] salt, byte[] serverPublic) {
        ByteBuffer buffer = ByteBuffer.allocate(SALT_LENGTH + 4 + 1 + serverPublic.length);
        buffer.put(salt);
        buffer.putInt(RECORD_SIZE);
        buffer.put((byte) serverPublic.length);
        buffer.put(serverPublic);
        return buffer.array();
    }

    private static byte[] ecdh(PrivateKey serverPrivate, PublicKey userAgentPublic)
            throws GeneralSecurityException {
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(serverPrivate);
        agreement.doPhase(userAgentPublic, true);
        return agreement.generateSecret();
    }

    /** RFC 5869 Extract + Expand (L ≤ 32라 Expand는 한 블록) */
    static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length)
            throws GeneralSecurityException {
        return hkdfExpand(hmac(salt, ikm), info, length);
    }

    static byte[] hkdfExpand(byte[] prk, byte[] info, int length) throws GeneralSecurityException {
        byte[] block = hmac(prk, concat(info, new byte[] {0x01}));
        byte[] out = new byte[length];
        System.arraycopy(block, 0, out, 0, length);
        return out;
    }

    static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
