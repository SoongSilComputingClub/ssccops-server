package org.sscc.ssccopsserver.tools;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;
import org.sscc.ssccopsserver.global.config.R2Config;

import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CORSConfiguration;
import software.amazon.awssdk.services.s3.model.CORSRule;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetBucketCorsRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutBucketCorsRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/*
 * R2 업로드 경로 진단 도구 (#137 · 출석 인증사진 · 로컬 전용).
 *
 * **이 클래스는 테스트 소스에만 있고 배포 아티팩트에 들어가지 않는다.** 하는 일은 브라우저가
 * 실제로 지나는 길을 서버에서 그대로 한 번 밟아 보는 것이며, 어느 칸에서 막히는지를 R2의 응답
 * 그대로 보여 준다 — 업로드 실패는 서버 로그에 아무것도 남기지 않아(PUT이 서버를 거치지 않는다)
 * 추측 말고는 좁힐 방법이 없었다.
 *
 * 다섯 칸을 순서대로 확인한다:
 *   1. 설정      account-id·bucket·키가 있는가, public-base-url이 공개 도메인인가
 *   2. 자격증명   HeadBucket — 이 키로 그 버킷에 닿는가
 *   3. CORS      버킷의 현재 규칙. --apply-cors를 주면 브라우저 PUT이 되는 규칙으로 덮어쓴다
 *   4. 서명 PUT   운영 코드와 같은 빈으로 서명해 실제 바이트를 올린다
 *   5. 프리플라이트 브라우저가 먼저 보내는 OPTIONS를 흉내 낸다 — CORS 문제를 여기서 확진한다
 *
 * **S3Client·S3Presigner를 새로 만들지 않고 R2Config를 그대로 띄운다.** 여기서 설정을 한 벌 더
 * 적으면 진단이 통과해도 운영 경로는 다른 설정으로 실패할 수 있다 — 확인해야 하는 것이 바로
 * 그 빈들이다.
 *
 * 실행:
 *   ./gradlew r2Check                                     (점검만)
 *   ./gradlew r2Check -Pargs="--apply-cors"               (CORS 규칙까지 적용)
 *   ./gradlew r2Check -Pargs="--apply-cors --origin=http://localhost:3000"
 *
 * 값은 환경변수에서 읽는다(.env가 있으면 Gradle이 주입한다):
 *   R2_ACCOUNT_ID · R2_ACCESS_KEY_ID · R2_SECRET_ACCESS_KEY · R2_BUCKET_NAME
 *   R2_PUBLIC_BASE_URL · FRONTEND_URL(쉼표로 여러 개, CORS 허용 오리진의 기본값)
 */
public final class R2Check {

    /** 진단 오브젝트도 인증사진과 같은 접두사 아래에 둔다 — 정리 규칙을 하나로 유지한다 */
    private static final String DIAGNOSTIC_KEY_PREFIX = "academic-programs/_diagnostics/";

    private static final String CONTENT_TYPE = "image/png";

    /** 1x1 투명 PNG. 진단에 필요한 것은 "바이트가 올라가는가"뿐이라 가장 작은 것을 쓴다 */
    private static final byte[] ONE_PIXEL_PNG =
            Base64.getDecoder()
                    .decode(
                            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mNkYAAAAAY"
                                    + "AAjCB0C8AAAAASUVORK5CYII=");

    private R2Check() {}

    public static void main(String[] args) {
        List<String> arguments = Arrays.asList(args);
        boolean applyCors = arguments.contains("--apply-cors");
        List<String> origins = originsFrom(arguments);

        Map<String, String> config = readConfig();
        printConfig(config, origins);
        if (!hasCredentials(config)) {
            return;
        }

        // 빈은 컨텍스트가 닫을 때 함께 닫힌다(@Bean의 close()가 소멸 메서드로 추론된다)
        try (AnnotationConfigApplicationContext context = springContext(config)) {
            S3Client client = context.getBean(S3Client.class);
            S3Presigner presigner = context.getBean(S3Presigner.class);

            String bucket = config.get("bucket");
            if (!headBucket(client, bucket)) {
                return;
            }
            printCors(client, bucket);
            if (applyCors) {
                applyCors(client, bucket, origins);
                printCors(client, bucket);
            }

            String key = DIAGNOSTIC_KEY_PREFIX + UUID.randomUUID() + ".png";
            String uploadUrl = presign(presigner, bucket, key);
            boolean uploaded = putBytes(uploadUrl);
            preflight(uploadUrl, origins.isEmpty() ? null : origins.get(0));

            if (uploaded) {
                client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
                System.out.println("  정리: 진단 오브젝트를 지웠습니다 (" + key + ")");
            }
        } catch (RuntimeException ex) {
            System.out.println("[실패] 진단 중 예외: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ 1. 설정

    private static Map<String, String> readConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("accountId", env("R2_ACCOUNT_ID"));
        config.put("accessKeyId", env("R2_ACCESS_KEY_ID"));
        config.put("secretAccessKey", env("R2_SECRET_ACCESS_KEY"));
        config.put("bucket", env("R2_BUCKET_NAME"));
        config.put("publicBaseUrl", env("R2_PUBLIC_BASE_URL"));
        return config;
    }

    private static void printConfig(Map<String, String> config, List<String> origins) {
        System.out.println("[1] 설정");
        System.out.println("  account-id     : " + mask(config.get("accountId")));
        System.out.println("  access-key-id  : " + mask(config.get("accessKeyId")));
        System.out.println("  secret         : " + mask(config.get("secretAccessKey")));
        System.out.println("  bucket         : " + orMissing(config.get("bucket")));
        System.out.println("  public-base-url: " + orMissing(config.get("publicBaseUrl")));
        System.out.println("  CORS 허용 오리진 : " + (origins.isEmpty() ? "(없음)" : origins));

        String publicBaseUrl = config.get("publicBaseUrl");
        if (publicBaseUrl != null && publicBaseUrl.contains(".r2.cloudflarestorage.com")) {
            System.out.println(
                    "  [경고] public-base-url이 S3 API 엔드포인트입니다 — 이 호스트는 서명 없는 GET에"
                            + " 언제나 401을 돌려주므로 <img src>로는 절대 열리지 않습니다."
                            + " 업로드에는 영향이 없습니다(PUT은 서명된 URL을 씁니다).");
        }
    }

    /*
     * 값이 없으면 여기서 끝낸다 — 그대로 진행하면 스프링 컨텍스트가 "Access key ID cannot be
     * blank"로 죽어, 설정이 비었다는 사실이 프레임워크 예외에 묻힌다.
     */
    private static boolean hasCredentials(Map<String, String> config) {
        List<String> missing =
                List.of("accountId", "accessKeyId", "secretAccessKey", "bucket").stream()
                        .filter(key -> config.get(key) == null || config.get(key).isBlank())
                        .toList();
        if (missing.isEmpty()) {
            return true;
        }
        System.out.println("  [중단] 값이 비어 있습니다: " + missing);
        System.out.println(
                "  .env에 채우거나(R2_ACCOUNT_ID·R2_ACCESS_KEY_ID·R2_SECRET_ACCESS_KEY·R2_BUCKET_NAME)"
                        + " 셸에서 export 한 뒤 다시 실행하세요.");
        return false;
    }

    /* 자격증명은 앞 4글자만 남긴다 — 진단 출력이 그대로 붙여넣기 되는 곳이 있다 */
    private static String mask(String value) {
        if (value == null || value.isBlank()) {
            return "(없음)";
        }
        return value.length() <= 4 ? "****" : value.substring(0, 4) + "****";
    }

    private static String orMissing(String value) {
        return value == null || value.isBlank() ? "(없음)" : value;
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? null : value.trim();
    }

    /*
     * --origin으로 준 값이 있으면 그것이고, 없으면 FRONTEND_URL이다(서버 CORS 허용 오리진과
     * 같은 값을 쓴다 — 버킷과 서버가 서로 다른 오리진을 허용하면 한쪽만 통과하는 상태가 된다).
     */
    private static List<String> originsFrom(List<String> arguments) {
        List<String> origins = new ArrayList<>();
        for (String argument : arguments) {
            if (argument.startsWith("--origin=")) {
                origins.add(argument.substring("--origin=".length()).trim());
            }
        }
        if (origins.isEmpty()) {
            String frontendUrl = env("FRONTEND_URL");
            if (frontendUrl != null && !frontendUrl.isBlank()) {
                Arrays.stream(frontendUrl.split(","))
                        .map(String::trim)
                        .filter(origin -> !origin.isBlank())
                        .forEach(origins::add);
            }
        }
        return origins;
    }

    private static AnnotationConfigApplicationContext springContext(Map<String, String> config) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment()
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "r2-check",
                                Map.of(
                                        "r2.account-id", nullToEmpty(config.get("accountId")),
                                        "r2.access-key-id", nullToEmpty(config.get("accessKeyId")),
                                        "r2.secret-access-key",
                                                nullToEmpty(config.get("secretAccessKey")))));
        context.register(PropertySourcesPlaceholderConfigurer.class);
        context.register(R2Config.class);
        context.refresh();
        return context;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    // ------------------------------------------------------------------ 2. 자격증명

    private static boolean headBucket(S3Client client, String bucket) {
        System.out.println("[2] 자격증명 · 버킷 접근 (HeadBucket)");
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            System.out.println("  통과 — 이 키로 버킷 '" + bucket + "'에 닿습니다.");
            return true;
        } catch (AwsServiceException ex) {
            System.out.println("  [실패] " + describe(ex));
            System.out.println("  키·버킷명·account-id 중 하나가 틀렸습니다. 여기서 막히면 아래 칸은 볼 필요가 없습니다.");
            return false;
        }
    }

    // ------------------------------------------------------------------ 3. CORS

    private static void printCors(S3Client client, String bucket) {
        System.out.println("[3] 버킷 CORS 규칙");
        try {
            List<CORSRule> rules =
                    client.getBucketCors(GetBucketCorsRequest.builder().bucket(bucket).build())
                            .corsRules();
            if (rules.isEmpty()) {
                System.out.println("  (규칙 없음)");
            }
            rules.forEach(
                    rule ->
                            System.out.println(
                                    "  origins="
                                            + rule.allowedOrigins()
                                            + " methods="
                                            + rule.allowedMethods()
                                            + " headers="
                                            + rule.allowedHeaders()
                                            + " maxAge="
                                            + rule.maxAgeSeconds()));
        } catch (AwsServiceException ex) {
            System.out.println("  [없음/실패] " + describe(ex));
            System.out.println(
                    "  CORS 규칙이 없으면 브라우저의 PUT은 서버에 닿기도 전에 차단됩니다" + " (--apply-cors로 넣을 수 있습니다).");
        }
    }

    /*
     * 브라우저에서 presigned PUT이 되기 위한 최소 규칙. Content-Type을 허용 헤더에 넣는 것이
     * 요점이다 — 그 값이 서명에 들어가 있어 웹이 반드시 함께 보내야 하고(발급 응답의
     * contentType), 허용 목록에 없으면 프리플라이트에서 막힌다.
     */
    private static void applyCors(S3Client client, String bucket, List<String> origins) {
        System.out.println("[3-1] CORS 규칙 적용");
        if (origins.isEmpty()) {
            System.out.println("  [건너뜀] 허용할 오리진이 없습니다 — FRONTEND_URL을 채우거나 --origin=... 로 주세요.");
            return;
        }
        CORSRule rule =
                CORSRule.builder()
                        .allowedOrigins(origins)
                        .allowedMethods("PUT", "GET", "HEAD")
                        .allowedHeaders("content-type")
                        .exposeHeaders("ETag")
                        .maxAgeSeconds(3600)
                        .build();
        client.putBucketCors(
                PutBucketCorsRequest.builder()
                        .bucket(bucket)
                        .corsConfiguration(CORSConfiguration.builder().corsRules(rule).build())
                        .build());
        System.out.println("  적용했습니다 (기존 규칙은 이 한 벌로 대체됩니다): " + origins);
    }

    // ------------------------------------------------------------------ 4. 서명 PUT

    private static String presign(S3Presigner presigner, String bucket, String key) {
        PutObjectRequest putObjectRequest =
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(CONTENT_TYPE)
                        .build();
        return presigner
                .presignPutObject(
                        PutObjectPresignRequest.builder()
                                .signatureDuration(Duration.ofMinutes(10))
                                .putObjectRequest(putObjectRequest)
                                .build())
                .url()
                .toString();
    }

    private static boolean putBytes(String uploadUrl) {
        System.out.println("[4] 서명된 PUT (운영 코드와 같은 빈으로 서명)");
        try {
            HttpResponse<String> response =
                    HttpClient.newHttpClient()
                            .send(
                                    HttpRequest.newBuilder(URI.create(uploadUrl))
                                            .header("Content-Type", CONTENT_TYPE)
                                            .PUT(
                                                    HttpRequest.BodyPublishers.ofByteArray(
                                                            ONE_PIXEL_PNG))
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 == 2) {
                System.out.println("  통과 — R2가 " + response.statusCode() + "로 받았습니다.");
                System.out.println("  서명·자격증명·키 규칙에는 문제가 없습니다.");
                return true;
            }
            System.out.println("  [실패] HTTP " + response.statusCode());
            System.out.println("  " + response.body());
            return false;
        } catch (Exception ex) {
            System.out.println("  [실패] " + ex.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------ 5. 프리플라이트

    /*
     * 브라우저는 Content-Type을 실은 PUT 앞에 OPTIONS를 먼저 보낸다. 4번이 통과하는데 화면에서만
     * 실패한다면 원인은 거의 언제나 여기다 — 서버 로그에는 아무것도 남지 않는다.
     */
    private static void preflight(String uploadUrl, String origin) {
        System.out.println("[5] 프리플라이트(OPTIONS) — 브라우저가 먼저 보내는 요청");
        if (origin == null) {
            System.out.println("  [건너뜀] 확인할 오리진이 없습니다 (FRONTEND_URL 또는 --origin=...).");
            return;
        }
        try {
            HttpResponse<Void> response =
                    HttpClient.newHttpClient()
                            .send(
                                    HttpRequest.newBuilder(URI.create(uploadUrl))
                                            .header("Origin", origin)
                                            .header("Access-Control-Request-Method", "PUT")
                                            .header(
                                                    "Access-Control-Request-Headers",
                                                    "content-type")
                                            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                                            .build(),
                                    HttpResponse.BodyHandlers.discarding());

            String allowOrigin =
                    response.headers().firstValue("access-control-allow-origin").orElse(null);
            System.out.println(
                    "  HTTP "
                            + response.statusCode()
                            + " · access-control-allow-origin="
                            + (allowOrigin == null ? "(없음)" : allowOrigin));
            if (allowOrigin == null) {
                System.out.println("  [실패] 이 오리진에는 브라우저 PUT이 차단됩니다 — --apply-cors로 규칙을 넣으세요.");
            } else {
                System.out.println("  통과 — 브라우저가 " + origin + " 에서 PUT 할 수 있습니다.");
            }
        } catch (Exception ex) {
            System.out.println("  [실패] " + ex.getMessage());
        }
    }

    private static String describe(AwsServiceException ex) {
        return "HTTP "
                + ex.statusCode()
                + " "
                + ex.awsErrorDetails().errorCode()
                + " — "
                + ex.awsErrorDetails().errorMessage();
    }
}
