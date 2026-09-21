package org.sscc.ssccopsserver.domain.notification.push;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;

/*
 * P-256(secp256r1) 키의 원시 바이트 ↔ JDK 키 객체 변환 (ssccops#446).
 *
 * Web Push의 어휘는 전부 **원시 바이트**다 — 브라우저의 `p256dh`는 비압축 점 65바이트
 * (`0x04 || X || Y`), `npx web-push generate-vapid-keys`가 주는 개인키는 스칼라 32바이트, 둘 다
 * base64url. JDK는 X.509/PKCS#8 인코딩만 바로 읽으므로 그 사이를 여기서 잇는다.
 *
 * **BouncyCastle을 쓰지 않는다.** tika-pdf가 bcprov-jdk18on을 들여오긴 했지만 그것은 암호화 PDF를
 * 위한 라이브러리 의존이지 JCA 프로바이더로 등록된 것이 아니다 — `Security.addProvider`로 전역
 * 등록하면 JVM 전체의 알고리즘 해석 순서가 바뀐다. 여기 필요한 것(EC 키 스펙 · ECDH · AES-GCM ·
 * HMAC · ECDSA)은 전부 JDK 17 표준 프로바이더에 있다.
 */
final class P256 {

    static final int PUBLIC_KEY_LENGTH = 65;
    static final int PRIVATE_KEY_LENGTH = 32;
    private static final int COORDINATE_LENGTH = 32;
    private static final byte UNCOMPRESSED_POINT = 0x04;

    private static final ECParameterSpec CURVE = loadCurve();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private P256() {}

    private static ECParameterSpec loadCurve() {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JDK에 secp256r1 파라미터가 없다", e);
        }
    }

    static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("P-256 키 쌍을 만들 수 없다", e);
        }
    }

    /** base64url(비압축 점 65바이트) → 공개키. 길이·접두 바이트·곡선 위의 점인지를 본다 */
    static PublicKey publicKeyOf(String base64Url) {
        return publicKeyOf(decode(base64Url));
    }

    static PublicKey publicKeyOf(byte[] uncompressed) {
        if (uncompressed.length != PUBLIC_KEY_LENGTH || uncompressed[0] != UNCOMPRESSED_POINT) {
            throw new IllegalArgumentException(
                    "P-256 공개키는 0x04로 시작하는 65바이트여야 한다 (길이 " + uncompressed.length + ")");
        }
        BigInteger affineX =
                new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 1 + COORDINATE_LENGTH));
        BigInteger affineY =
                new BigInteger(
                        1,
                        Arrays.copyOfRange(uncompressed, 1 + COORDINATE_LENGTH, PUBLIC_KEY_LENGTH));
        requireOnCurve(affineX, affineY);
        try {
            return KeyFactory.getInstance("EC")
                    .generatePublic(new ECPublicKeySpec(new ECPoint(affineX, affineY), CURVE));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("P-256 공개키가 아니다", e);
        }
    }

    /*
     * 점이 곡선 위에 있는지 — RFC 8291 §7이 요구하는 확인이다(무효한 점으로 ECDH를 하면 개인키가
     * 샌다). **JDK의 KeyFactory는 이것을 검사하지 않는다**(KeyAgreement.doPhase에서야 던진다 —
     * 실측). 구독 등록은 아무 문자열이나 받으므로 발송 전에 여기서 거른다.
     * y² ≡ x³ + ax + b (mod p), 좌표는 [0, p).
     */
    private static void requireOnCurve(BigInteger affineX, BigInteger affineY) {
        BigInteger prime = ((ECFieldFp) CURVE.getCurve().getField()).getP();
        if (affineX.signum() < 0
                || affineX.compareTo(prime) >= 0
                || affineY.signum() < 0
                || affineY.compareTo(prime) >= 0) {
            throw new IllegalArgumentException("P-256 곡선 위의 점이 아니다 (좌표 범위)");
        }
        BigInteger left = affineY.multiply(affineY).mod(prime);
        BigInteger right =
                affineX.pow(3)
                        .add(CURVE.getCurve().getA().multiply(affineX))
                        .add(CURVE.getCurve().getB())
                        .mod(prime);
        if (!left.equals(right)) {
            throw new IllegalArgumentException("P-256 곡선 위의 점이 아니다");
        }
    }

    /** base64url(스칼라 32바이트) → 개인키 */
    static PrivateKey privateKeyOf(String base64Url) {
        byte[] scalar = decode(base64Url);
        if (scalar.length != PRIVATE_KEY_LENGTH) {
            throw new IllegalArgumentException("P-256 개인키는 32바이트여야 한다 (길이 " + scalar.length + ")");
        }
        try {
            return KeyFactory.getInstance("EC")
                    .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, scalar), CURVE));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("P-256 개인키가 아니다", e);
        }
    }

    /** 공개키 → 비압축 점 65바이트 (`0x04 || X || Y`, 좌표는 32바이트로 왼쪽 0 채움) */
    static byte[] uncompressedBytes(PublicKey publicKey) {
        ECPoint point = ((ECPublicKey) publicKey).getW();
        byte[] out = new byte[PUBLIC_KEY_LENGTH];
        out[0] = UNCOMPRESSED_POINT;
        copyPadded(point.getAffineX(), out, 1);
        copyPadded(point.getAffineY(), out, 1 + COORDINATE_LENGTH);
        return out;
    }

    /*
     * BigInteger.toByteArray()는 부호 비트 때문에 33바이트가 되거나 앞자리 0이 빠져 31바이트가 될 수
     * 있다 — 고정 32바이트 자리에 오른쪽 정렬로 옮긴다.
     */
    private static void copyPadded(BigInteger value, byte[] target, int offset) {
        byte[] raw = value.toByteArray();
        int start = raw.length > COORDINATE_LENGTH ? raw.length - COORDINATE_LENGTH : 0;
        int length = raw.length - start;
        System.arraycopy(raw, start, target, offset + (COORDINATE_LENGTH - length), length);
    }

    static byte[] decode(String base64Url) {
        try {
            return URL_DECODER.decode(base64Url.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("base64url이 아니다", e);
        }
    }
}
