package org.sscc.ssccopsserver.global.config;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
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
 *
 * **이 줄은 Kibana 의 «배포 시점»이기도 하다** (#426 · ssccops#340). 모든 로그 줄에
 * service.version 이 실리므로(ADR-0024 후속) 이 한 줄만 고를 수 있으면 «배포 뒤 오류율이
 * 변했나»가 성립한다 — 그래서 `event.action = app.start` 를 싣고, git sha 를
 * `labels.git_commit` 으로 싣는다. sha 는 deploy-history 레코드의 git_sha 와 같은 값이라
 * (ADR-0033) Kibana 줄과 배포 레코드가 버전뿐 아니라 커밋으로도 이어진다. GitProperties 도
 * Optional 이다 — 없으면 키를 내지 않는다(빈 값을 지어내면 Kibana 에서 커밋처럼 세어진다,
 * service.version 과 같은 규칙). 필드 이름의 정본은 deploy/kibana/log-schema.md 다.
 *
 * Coolify 빌드 로그를 ES 로 보내는 안은 기각했다 — ingress·자격이 하나 더 필요하고 웹(Vercel·
 * Cloudflare)은 어차피 빠지며, «깨졌다»는 신호는 로그 저장이 아니라 알림(Coolify Email)이 빠르다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BuildVersionLogger implements ApplicationRunner {

    private static final DateTimeFormatter BUILT_AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Seoul"));

    static final String EVENT_ACTION = "app.start";

    private final Optional<BuildProperties> buildProperties;
    private final Optional<GitProperties> gitProperties;
    private final Environment environment;

    @Override
    public void run(ApplicationArguments args) {
        String profiles = String.join(",", environment.getActiveProfiles());
        String commit = gitProperties.map(GitProperties::getCommitId).orElse(null);
        // 구조화 인자는 {} 자리 뒤에 잇는다 — 인코더가 kv 의 Map 을 루트에 중첩 객체로 낸다
        // (RequestLogFields·AuditLog 와 같은 길). labels 는 sha 가 있을 때만 싣는다 — 빈 객체를
        // 내면 «커밋 없음»이 값처럼 보인다.
        List<Object> structured = new ArrayList<>();
        structured.add(kv("event", Map.of("action", EVENT_ACTION)));
        if (commit != null) {
            structured.add(kv("labels", Map.of("git_commit", commit)));
        }
        if (buildProperties.isPresent()) {
            BuildProperties build = buildProperties.get();
            List<Object> all = new ArrayList<>();
            all.add(build.getVersion());
            all.add(profiles);
            all.add(BUILT_AT.format(build.getTime()));
            all.add(commit == null ? "없음" : commit.substring(0, Math.min(7, commit.length())));
            all.addAll(structured);
            log.info("ssccops-server {} 기동 — 프로필 {} · 빌드 {} · 커밋 {}", all.toArray());
        } else {
            List<Object> all = new ArrayList<>();
            all.add(profiles);
            all.addAll(structured);
            log.info(
                    "ssccops-server 기동 — 프로필 {} · 빌드 정보 없음 (bootJar 를 거치지 않은 실행이다)", all.toArray());
        }
    }
}
