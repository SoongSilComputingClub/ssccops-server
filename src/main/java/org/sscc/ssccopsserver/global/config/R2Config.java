package org.sscc.ssccopsserver.global.config;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

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
}
