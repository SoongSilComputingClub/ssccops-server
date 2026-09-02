package org.sscc.ssccopsserver.global.config;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/*
 * Cloudflare R2는 S3 호환 API를 쓰지만 진짜 AWS가 아니므로, SDK 기본값 중 AWS 전용 동작은
 * 꺼야 한다: 리전은 R2가 요구하는 고정값 "auto"이고(리전별로 나뉘지 않는다), 버킷 주소를
 * `<bucket>.<endpoint>`가 아니라 `<endpoint>/<bucket>` 경로로 풀어야 하므로
 * forcePathStyle(true)가 필요하다 — 끄면 R2가 모르는 가상 호스트 이름으로 요청이 나가
 * 연결 자체가 실패한다.
 */
@Configuration
public class R2Config {

    @Value("${r2.account-id}")
    private String accountId;

    @Value("${r2.access-key-id}")
    private String accessKeyId;

    @Value("${r2.secret-access-key}")
    private String secretAccessKey;

    @Bean
    public S3Client r2Client() {
        return S3Client.builder()
                .endpointOverride(URI.create("https://" + accountId + ".r2.cloudflarestorage.com"))
                .region(Region.of("auto"))
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                .forcePathStyle(true)
                .build();
    }

    /*
     * presigned URL 발급기 (#161 · wave2 D6). 서버가 파일 바이트를 다루지 않고 업로드를
     * 허락하기만 하는 구조라, 실제 PUT은 이 빈이 서명한 URL로 브라우저가 직접 보낸다.
     *
     * 설정은 S3Client와 **같은 값이어야 한다** — 엔드포인트·리전이 갈리면 서명은 성공하는데
     * R2가 거절하는 URL이 나가고, 그 실패는 서버 로그가 아니라 브라우저에서만 보인다.
     * 경로 스타일은 클라이언트의 forcePathStyle(true)에 대응하는 S3Configuration으로 켠다
     * (프리사이너에는 forcePathStyle 단축 설정이 없다).
     */
    @Bean
    public S3Presigner r2Presigner() {
        return S3Presigner.builder()
                .endpointOverride(URI.create("https://" + accountId + ".r2.cloudflarestorage.com"))
                .region(Region.of("auto"))
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                .serviceConfiguration(
                        S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }
}
