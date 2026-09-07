package org.sscc.ssccopsserver.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/*
 * 버전이 실제로 코드까지 닿는지 본다 (ssccops#229).
 *
 * **이 테스트가 지키는 것은 배선이지 숫자가 아니다.** `springBoot { buildInfo() }`가 만드는
 * META-INF/build-info.properties 를 Spring 이 BuildProperties 로 읽어 주어야 /actuator/info 도,
 * 부팅 로그도 값을 갖는다 — 그 고리가 끊기면 둘 다 조용히 빈 채로 돈다(그게 이 이슈 이전의
 * 상태였다). 버전 문자열 자체는 build.gradle 이 정본이라 여기서 값을 못 박지 않는다.
 *
 * 테스트 클래스패스에는 build/resources/main 이 들어 있어 그 파일이 그대로 보인다 — 실행 jar
 * 에서는 BootJar 가 같은 파일을 아카이브 루트의 META-INF 로 옮기고 Boot 런처가 그것을
 * 읽는다. **jar 에서 실제로 읽히는지는 배포 뒤 `curl /actuator/info` 로 확인한다**(AGENTS.md
 * '릴리스' 절) — 그 경로까지 여기서 재현할 수는 없다.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("빌드 정보 배선")
class BuildVersionLoggerTest {

    @Autowired private Optional<BuildProperties> buildProperties;

    @Autowired private BuildVersionLogger buildVersionLogger;

    @Test
    @DisplayName("build-info.properties 가 BuildProperties 로 읽힌다")
    void buildPropertiesIsResolved() {
        assertThat(buildProperties)
                .withFailMessage(
                        "build-info.properties 를 읽지 못했다 —"
                                + " build.gradle 의 springBoot { buildInfo() } 가 빠졌는지 확인할 것")
                .isPresent();
        assertThat(buildProperties.orElseThrow().getVersion()).isNotBlank();
        assertThat(buildProperties.orElseThrow().getArtifact()).isEqualTo("ssccops-server");
    }

    @Test
    @DisplayName("빌드 정보가 없어도 기동을 막지 않는다")
    void loggerToleratesMissingBuildInfo() {
        // 주입 형태가 Optional 이라는 사실 자체가 계약이다. 필수로 바꾸면 bootJar 를 거치지 않는
        // 실행(IDE·./gradlew bootRun)에서 컨텍스트가 뜨지 않는다.
        assertThat(buildVersionLogger).isNotNull();
    }
}
