package org.sscc.ssccopsserver.global.config;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/*
 * 부팅할 때 버전을 한 줄 남긴다 (ssccops#229).
 *
 * **배포 로그가 "무엇이 올라갔는가"에 답해야 하기 때문이다.** 배포는 저장소가 아니라 Coolify가
 * 하고(#202) develop 푸시가 dev로, main 푸시가 prod로 그대로 나간다 — 사람이 누른 것과 실제로
 * 뜬 것 사이에 확인할 자리가 없었다. 무언가 잘못됐을 때 가장 먼저 묻는 것이 "그 배포가 맞나"인데,
 * 그동안은 그 질문에 답할 수 있는 곳이 어디에도 없었다.
 *
 * /actuator/info 와 **같은 출처(BuildProperties)를 쓴다.** 버전 문자열을 여기 따로 적으면 같은
 * 사실이 두 벌이 되어 다음 릴리스에 한쪽만 오른다 — 이 이슈가 고치는 것이 정확히 그 상태다.
 *
 * BuildProperties 는 build-info.properties 가 산출물에 있을 때만 만들어진다. 테스트는 그 파일
 * 없이 도는 컨텍스트가 있으므로(bootJar 를 거치지 않는다) Optional 로 받는다 — 없으면 조용히
 * 지나간다. 여기서 부팅을 세우면 버전을 찍자고 앱을 못 뜨게 하는 것이 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BuildVersionLogger implements ApplicationRunner {

    private static final DateTimeFormatter BUILT_AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Seoul"));

    private final Optional<BuildProperties> buildProperties;
    private final Environment environment;

    @Override
    public void run(ApplicationArguments args) {
        String profiles = String.join(",", environment.getActiveProfiles());
        buildProperties.ifPresentOrElse(
                build ->
                        log.info(
                                "ssccops-server {} 기동 — 프로필 {} · 빌드 {}",
                                build.getVersion(),
                                profiles,
                                BUILT_AT.format(build.getTime())),
                () ->
                        log.info(
                                "ssccops-server 기동 — 프로필 {} · 빌드 정보 없음"
                                        + " (bootJar 를 거치지 않은 실행이다)",
                                profiles));
    }
}
