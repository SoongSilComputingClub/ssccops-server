package org.sscc.ssccopsserver.tools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.sscc.ssccopsserver.SsccopsServerApplication;
import org.sscc.ssccopsserver.domain.assistant.dto.AssistantCitationResponse;
import org.sscc.ssccopsserver.domain.assistant.dto.AssistantQueryRequest;
import org.sscc.ssccopsserver.domain.assistant.dto.AssistantQueryResponse;
import org.sscc.ssccopsserver.domain.assistant.service.AssistantService;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.support.AssistantBenchmarkSet;
import org.sscc.ssccopsserver.support.AssistantBenchmarkSet.Scenario;
import org.sscc.ssccopsserver.support.AssistantBenchmarkSet.Turn;

import reactor.core.publisher.Flux;

/*
 * 규정 도우미 실측 벤치마크 (#465 · 로컬 전용 · `./gradlew ragBench`).
 *
 * **`GeminiCheck`·`R2Check`와 같은 자리다** — 실제 자격증명과 **실제로 적재된 로컬 DB**가
 * 있어야만 답이 나오는 진단이라 `./gradlew test` 에 섞지 않는다. 배포 아티팩트에도 들어가지
 * 않는다(테스트 소스).
 *
 * ══ 무엇을 지나는가 ════════════════════════════════════════════
 *
 * 스프링 컨텍스트를 그대로 띄우고 **`AssistantService.query`를 부른다.** HTTP 를 지나지 않는
 * 것은 Supabase 토큰이 필요 없어지기 때문이고(컨트롤러 위층은 이 기능의 측정 대상이 아니다),
 * 검색·프롬프트·인용 해석은 **운영과 글자 하나까지 같은 코드**다. 대화도 진짜 대화라 이어 묻기가
 * 실제로 이어진다.
 *
 * ══ 모드가 둘이다 — 기본은 모델을 부르지 않는다 ═════════════════
 *
 * | | 채팅 모델 | 재는 것 | 쿼터 |
 * |---|---|---|---|
 * | **검색**(기본) | **스텁** — 발췌 번호를 전부 인용한다 | **발췌에 정답이 들었는가**와 그 순위 | 임베딩만 |
 * | `--answer` | 실제 Gemini | 답했는가 · 무엇을 인용했는가 | 임베딩 + 채팅 |
 *
 * **기본이 검색 모드인 것은 무료 티어의 일일 채팅 한도 때문이다** — 이 시험지 한 바퀴가 28턴이라
 * 실측 하루치를 한 번에 태운다(3.6-flash 에서 `…PerDayPerProjectPerModel-FreeTier`가 20이었다 ·
 * 루트 AGENTS.md). 그리고 **보고된 고장이 검색 단계의 고장이다**: 「그 다음 조」에서 제4조가
 * 발췌에 없었고, 모델은 그것을 보고 올바르게 `[근거없음]`을 냈다. 고칠 자리도 재야 할 자리도
 * 검색이다.
 *
 * ⚠️ **스텁이 「전부 인용」인 것이 계약이다.** 그래야 {@code AssistantQueryResponse.citations}가
 * **모델에게 넣어 준 발췌 목록 그대로**가 되어(순서까지) 재현율과 순위를 잴 수 있다. 동시에
 * 첫 인용이 발췌 1번이라 **이어 묻기의 기준점도 운영과 같아진다** — 발췌 1번은 조를 지목한
 * 질의에서 핀으로 집어 온 그 조다({@code AssistantServiceImpl.retrieve}가 핀을 먼저 담는다).
 *
 * ══ 실행 ═══════════════════════════════════════════════════════
 *
 *   ./gradlew ragBench
 *   ./gradlew ragBench -Pargs="--label=before"
 *   ./gradlew ragBench -Pargs="--only=B --answer"
 *   ./gradlew ragBench -Pargs="--label=after --compare=build/rag-bench/before.tsv"
 *
 * 결과는 `build/rag-bench/{label}.tsv` 에 남는다 — 고치기 전후를 나란히 놓으려면 그 파일을
 * `--compare` 에 준다.
 */
public final class RagBenchmark {

    private static final Path OUT_DIR = Path.of("build", "rag-bench");

    /** 스텁이 내는 답 — 발췌 번호를 넉넉히 적는다. 범위 밖은 {@code CitationVerifier}가 버린다 */
    private static final int STUB_ANSWER_CITATIONS = 16;

    /*
     * 벤치마크가 지나지 않는 배선의 **자리 채우기**.
     *
     * `local` 프로파일은 기본값 없는 환경변수 넷을 요구하는데(R2 셋 · Supabase 하나) 이 도구는
     * HTTP 도 파일도 지나지 않아 값이 무엇이든 쓰이지 않는다. 그런데도 없으면 **부팅이 깨진다** —
     * 「비어 있을 정당한 이유가 없다」가 그 값들의 성질이기 때문이고(루트 AGENTS.md), 그 판단을
     * 벤치마크 하나 때문에 느슨하게 만들지 않는다.
     *
     * ⚠️ **`properties(...)`로 넣는다 — `run(...)` 인자가 아니다.** 앞은 가장 낮은 우선순위라
     * 실제 환경변수가 있으면 그쪽이 이기고, 뒤는 가장 높아 **진짜 값을 덮어쓴다.**
     */
    private static final Map<String, Object> UNUSED_CREDENTIALS =
            Map.of(
                    "SUPABASE_URL", "https://benchmark.invalid",
                    "R2_ACCOUNT_ID", "benchmark",
                    "R2_ACCESS_KEY_ID", "benchmark",
                    "R2_SECRET_ACCESS_KEY", "benchmark");

    private RagBenchmark() {}

    public static void main(String[] args) {
        Options options = Options.parse(args);

        System.out.println("═══ 규정 도우미 벤치마크 ═══");
        System.out.printf(
                "  모드=%s  대상=%s  라벨=%s%n",
                options.answerMode ? "answer(실제 Gemini)" : "retrieval(스텁 모델)",
                options.only.isEmpty() ? "전부" : options.only,
                options.label);

        List<Scenario> scenarios =
                AssistantBenchmarkSet.SCENARIOS.stream().filter(options::selected).toList();
        if (scenarios.isEmpty()) {
            System.out.println("고른 시나리오가 없다 — --only 값을 확인할 것");
            return;
        }

        try (ConfigurableApplicationContext context = boot(options)) {
            requireExpectedModel(context, options);
            MemberEntity member = anyMember(context);
            AssistantService assistant = context.getBean(AssistantService.class);
            List<Row> rows = new ArrayList<>();
            for (Scenario scenario : scenarios) {
                run(assistant, member, scenario, rows, options);
            }
            report(rows, options);
        }
    }

    /*
     * 컨텍스트는 `local` 프로파일 그대로다 — **재려는 것이 로컬에 적재된 그 코퍼스**이기
     * 때문이다. 넷만 덮어쓴다:
     *
     *   server.port=0        이미 떠 있는 서버(8080)와 부딪히지 않는다
     *   assistant.enabled    기본이 꺼짐이라 켜 주지 않으면 전부 404다
     *   indexing.auto=false  벤치마크가 대기열을 돌며 임베딩 쿼터를 태우지 않는다
     *   rate-limit           28턴이 분당 5회·전역 7회에 걸린다 — 재는 동안은 연다
     *
     * ⚠️ **프로퍼티는 `run(...)` 인자로 준다.** `builder.properties(...)`는 «기본 프로퍼티»라
     * `application.yaml`의 `${PORT:8080}`에 밀린다(`McpStatelessRedeployTest`가 같은 자리에서
     * 8080에 떴다).
     */
    private static ConfigurableApplicationContext boot(Options options) {
        SpringApplicationBuilder builder =
                options.answerMode
                        ? new SpringApplicationBuilder(SsccopsServerApplication.class)
                        : new SpringApplicationBuilder(
                                SsccopsServerApplication.class, StubChatConfig.class);
        List<String> springArgs =
                new ArrayList<>(
                        List.of(
                                "--server.port=0",
                                "--ssccops.assistant.enabled=true",
                                "--ssccops.assistant.indexing.auto=false",
                                "--ssccops.assistant.rate-limit.member-per-minute=10000",
                                "--ssccops.assistant.rate-limit.member-per-day=10000",
                                "--ssccops.assistant.rate-limit.global-per-minute=10000",
                                "--logging.level.root=ERROR",
                                "--spring.jpa.show-sql=false",
                                "--logging.level.org.hibernate.SQL=OFF",
                                /*
                                 * 서비스의 질의 로그(`발췌=N` · 거절 사유)는 **`--verbose`에서만**
                                 * 켠다. 켜 두면 표 한 줄마다 로그 한 줄이 끼어들어 벤치마크의
                                 * 결과표가 읽히지 않는데, 판정이 왜 그렇게 나왔는지를 따질 때는
                                 * 그 줄이 유일한 단서다.
                                 */
                                "--logging.level.org.sscc.ssccopsserver.domain.assistant="
                                        + (options.verbose ? "INFO" : "WARN")));
        springArgs.addAll(options.springArgs);
        return builder.profiles("local")
                .properties(UNUSED_CREDENTIALS)
                .run(springArgs.toArray(String[]::new));
    }

    /*
     * 재고 있는 모델이 재려던 모델인가 — **모드와 배선이 갈리면 멈춘다**(위 ⚠️).
     */
    private static void requireExpectedModel(
            ConfigurableApplicationContext context, Options options) {

        boolean stubbed = context.containsBean("benchmarkChatClient");
        if (stubbed == options.answerMode) {
            throw new IllegalStateException(
                    "모드와 채팅 배선이 어긋난다 — 모드=%s 스텁=%s"
                            .formatted(options.answerMode ? "answer" : "retrieval", stubbed));
        }
        System.out.printf("  채팅 모델=%s%n", stubbed ? "스텁(전부 인용)" : "실제 Gemini");
    }

    /*
     * 서비스가 회원에게서 읽는 것은 식별자 하나다(`AssistantServiceImpl.prepare`) — 그래도
     * 지어내지 않고 실제 행을 집는 것은, 대화 식별자의 앞부분이 그 값이라 **운영과 같은 키**로
     * 캐시에 들어가게 하기 위해서다.
     */
    private static MemberEntity anyMember(ConfigurableApplicationContext context) {
        return context.getBean(MemberRepository.class).findAll().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("로컬 DB에 회원이 없다 — 한 번 가입한 뒤 다시 돌릴 것"));
    }

    /** 한 시나리오 = 한 대화. 두 번째 턴부터는 <b>받은 {@code conversationId}를 그대로 싣는다</b> */
    private static void run(
            AssistantService assistant,
            MemberEntity member,
            Scenario scenario,
            List<Row> rows,
            Options options) {

        String conversationId = null;
        int index = 0;
        for (Turn turn : scenario.turns()) {
            index++;
            Instant startedAt = Instant.now();
            AssistantQueryResponse response;
            try {
                response =
                        assistant.query(
                                new AssistantQueryRequest(turn.question(), conversationId), member);
            } catch (RuntimeException failure) {
                rows.add(Row.failed(scenario, index, turn, message(failure)));
                System.out.printf(
                        "  %-3s %d/%d  ✖ %s%n",
                        scenario.id(), index, scenario.turns().size(), message(failure));
                return;
            }
            conversationId = response.conversationId();
            Row row =
                    Row.of(
                            scenario,
                            index,
                            turn,
                            response,
                            Duration.between(startedAt, Instant.now()).toMillis(),
                            options.answerMode);
            rows.add(row);
            System.out.println("  " + row.line());
            if (options.answerMode) {
                // 실제 모델을 부른 모드에서는 **답 자체가 결과다** — 점수표가 그것을 대신하지 못한다
                System.out.println("       ↳ " + response.answer().replace("\n", " "));
            }
        }
    }

    // ── 채점 ───────────────────────────────────────────────────

    /*
     * 한 턴의 결과.
     *
     * **`rank`는 발췌 목록에서 정답이 처음 나타난 자리다**(1부터). 검색 모드에서는 인용 목록이
     * 곧 발췌 목록이라 이 값이 그대로 검색 순위이고, `--answer` 모드에서는 «모델이 몇 번째로
     * 든 근거인가»라 뜻이 다르다 — 두 모드의 수를 섞어 평균 내지 말 것.
     */
    private record Row(
            String id,
            String tag,
            int turnIndex,
            int turnCount,
            String question,
            List<String> expected,
            List<String> got,
            boolean answered,
            int matched,
            int rank,
            long elapsedMs,
            String error) {

        static Row of(
                Scenario scenario,
                int index,
                Turn turn,
                AssistantQueryResponse response,
                long elapsedMs,
                boolean answerMode) {

            /*
             * ⚠️ **한글을 NFC 로 맞춰 견준다.** 평문 문서의 인용 표기는 문서 이름이고 그 이름은
             * macOS 가 올린 **파일 이름**에서 왔다 — APFS 가 자모를 분리해 저장하므로(NFD) 소스에
             * 적은 `회칙개정_2026_검토목록`(NFC)과 **눈으로는 같고 `equals`로는 다르다.** 이것을
             * 맞추지 않아 평문 두 건이 근거를 제대로 물어 오고도 MISS 로 찍혔다.
             */
            List<String> got =
                    response.citations().stream()
                            .map(AssistantCitationResponse::marker)
                            .map(RagBenchmark::nfc)
                            .toList();
            List<String> expected = turn.expected().stream().map(RagBenchmark::nfc).toList();
            int matched = (int) expected.stream().filter(got::contains).count();
            int rank =
                    expected.stream().mapToInt(got::indexOf).filter(at -> at >= 0).min().orElse(-2)
                            + 1;
            return new Row(
                    scenario.id(),
                    scenario.tag(),
                    index,
                    scenario.turns().size(),
                    turn.question(),
                    expected,
                    got,
                    response.answered(),
                    matched,
                    rank,
                    elapsedMs,
                    null);
        }

        static Row failed(Scenario scenario, int index, Turn turn, String error) {
            return new Row(
                    scenario.id(),
                    scenario.tag(),
                    index,
                    scenario.turns().size(),
                    turn.question(),
                    turn.expected(),
                    List.of(),
                    false,
                    0,
                    -1,
                    0,
                    error);
        }

        /** 거절이 정답인 턴인가 */
        boolean mustRefuse() {
            return expected.isEmpty();
        }

        boolean passed() {
            return mustRefuse() ? !answered : matched == expected.size();
        }

        /** 기대한 근거를 <b>일부만</b> 물어 왔는가 — 여러 조를 기대하는 턴에서 «전무»와 «일부»를 가른다 */
        boolean partial() {
            return !mustRefuse() && matched > 0 && matched < expected.size();
        }

        String verdict() {
            if (error != null) {
                return "ERR ";
            }
            if (passed()) {
                return "HIT ";
            }
            return partial() ? "PART" : "MISS";
        }

        String line() {
            return "%-3s %d/%d  %s  기대=%-22s 순위=%-3s 인용=%d  %4dms  %s"
                    .formatted(
                            id,
                            turnIndex,
                            turnCount,
                            verdict(),
                            mustRefuse() ? "(거절)" : String.join(",", expected),
                            rank > 0 ? String.valueOf(rank) : "-",
                            got.size(),
                            elapsedMs,
                            abbreviate(question));
        }
    }

    private static void report(List<Row> rows, Options options) {
        System.out.println();
        System.out.println("── 군별 ─────────────────────────────────");
        Map<String, int[]> byTag = new LinkedHashMap<>();
        for (Row row : rows) {
            if (row.mustRefuse() && !options.answerMode) {
                continue; // 스텁 모델에는 거절의 주체가 없다 — 판정하지 않는다
            }
            int[] counter = byTag.computeIfAbsent(row.tag(), key -> new int[3]);
            counter[0]++;
            if (row.passed()) {
                counter[1]++;
            }
            if (row.partial()) {
                counter[2]++;
            }
        }
        int total = 0;
        int passed = 0;
        for (Map.Entry<String, int[]> entry : byTag.entrySet()) {
            int[] counter = entry.getValue();
            total += counter[0];
            passed += counter[1];
            System.out.printf(
                    "  %-14s %2d/%-2d  (%.0f%%)  부분적중 %d%n",
                    entry.getKey(),
                    counter[1],
                    counter[0],
                    100.0 * counter[1] / counter[0],
                    counter[2]);
        }
        System.out.printf(
                "  %-14s %2d/%-2d  (%.0f%%)%n", "합계", passed, total, 100.0 * passed / total);
        System.out.printf(
                "  중앙 지연  %dms%n",
                rows.stream()
                        .mapToLong(Row::elapsedMs)
                        .sorted()
                        .skip(rows.size() / 2L)
                        .findFirst()
                        .orElse(0));

        Path out = write(rows, options.label);
        System.out.println("  기록 → " + out);
        options.compare.ifPresent(path -> compare(path, rows));
    }

    private static Path write(List<Row> rows, String label) {
        StringBuilder tsv =
                new StringBuilder(
                        "id\ttag\tturn\tverdict\texpected\tgot\tanswered\trank\tms\tquestion\n");
        for (Row row : rows) {
            tsv.append(row.id())
                    .append('\t')
                    .append(row.tag())
                    .append('\t')
                    .append(row.turnIndex())
                    .append('\t')
                    .append(row.verdict().strip())
                    .append('\t')
                    .append(cells(row.expected()))
                    .append('\t')
                    .append(cells(row.got()))
                    .append('\t')
                    .append(row.answered())
                    .append('\t')
                    .append(row.rank())
                    .append('\t')
                    .append(row.elapsedMs())
                    .append('\t')
                    .append(row.question())
                    .append('\n');
        }
        try {
            Files.createDirectories(OUT_DIR);
            Path out = OUT_DIR.resolve(label + ".tsv");
            Files.writeString(out, tsv.toString(), StandardCharsets.UTF_8);
            return out;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /*
     * 고치기 전후를 나란히 — **바뀐 줄만 찍는다.** 같은 줄까지 찍으면 바뀐 것이 묻힌다.
     */
    private static void compare(Path before, List<Row> after) {
        Map<String, String> was = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(before, StandardCharsets.UTF_8)) {
                String[] cells = line.split("\t");
                if (cells.length > 3 && !"id".equals(cells[0])) {
                    was.put(cells[0] + "/" + cells[2], cells[3]);
                }
            }
        } catch (IOException failure) {
            System.out.println("  비교 대상을 읽지 못했다 — " + before);
            return;
        }
        System.out.println("── " + before.getFileName() + " 대비 ─────────────────");
        boolean changed = false;
        for (Row row : after) {
            String key = row.id() + "/" + row.turnIndex();
            String previous = was.get(key);
            if (previous != null && !previous.equals(row.verdict().strip())) {
                changed = true;
                System.out.printf(
                        "  %-6s %s → %s   %s%n",
                        key, previous, row.verdict().strip(), abbreviate(row.question()));
            }
        }
        if (!changed) {
            System.out.println("  바뀐 판정 없음");
        }
    }

    /** 빈 칸을 `-`로 — TSV 에서 빈 문자열은 열을 통째로 밀어 «거절 줄만 어긋난 표»가 된다 */
    private static String cells(List<String> values) {
        return values.isEmpty() ? "-" : String.join(",", values);
    }

    private static String nfc(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC);
    }

    private static String abbreviate(String text) {
        return text.length() <= 30 ? text : text.substring(0, 29) + "…";
    }

    private static String message(RuntimeException failure) {
        return failure.getMessage() == null
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
    }

    // ── 스텁 모델 ───────────────────────────────────────────────

    /*
     * 발췌 번호를 전부 인용하는 모델.
     *
     * `@Primary`라 실제 Gemini 클라이언트가 함께 떠 있어도 이쪽이 주입된다 —
     * `spring.ai.model.chat=none`으로 그쪽을 끄지 않은 것은 같은 프로퍼티가 임베딩 배선까지
     * 함께 흔들기 때문이다(`GeminiWiringEnvironmentPostProcessor`).
     *
     * ⚠️ **`@Configuration`을 붙이지 않는다 — 붙이면 `--answer` 모드에서도 이것이 쓰인다.**
     * 이 클래스가 `org.sscc.ssccopsserver` 아래에 있어 컴포넌트 스캔이 집어 가기 때문이고,
     * 그러면 실제 모델을 부른다고 믿으면서 스텁을 재게 된다(실제로 한 번 그랬다 — 채팅 왕복이
     * 없는데도 `소요=859ms`였고 `버린인용=10`이 스텁의 열여섯 번호를 그대로 드러냈다).
     * 애노테이션이 없는 `@Bean` 보유 클래스는 스캔에 걸리지 않고, `SpringApplicationBuilder`에
     * **직접 넘겼을 때만** lite 설정으로 처리된다. 그 계약을 아래 {@code requireExpectedModel}이
     * 지킨다 — 주석은 지워질 수 있지만 그 검사는 부팅을 멈춘다.
     */
    static class StubChatConfig {

        @Bean
        @Primary
        ChatClient benchmarkChatClient() {
            return ChatClient.builder(new CiteEverything()).build();
        }
    }

    private static final class CiteEverything implements ChatModel {

        private static final String ANSWER = citations();

        private static String citations() {
            StringBuilder text = new StringBuilder("발췌를 그대로 가리킨다 ");
            for (int number = 1; number <= STUB_ANSWER_CITATIONS; number++) {
                text.append('[').append(number).append(']');
            }
            return text.toString();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(ANSWER))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }
    }

    // ── 인자 ───────────────────────────────────────────────────

    private record Options(
            boolean answerMode,
            boolean verbose,
            List<String> only,
            String label,
            Optional<Path> compare,
            List<String> springArgs) {

        static Options parse(String[] args) {
            boolean answerMode = false;
            boolean verbose = false;
            List<String> only = new ArrayList<>();
            String label = null;
            Path compare = null;
            List<String> springArgs = new ArrayList<>();
            for (String arg : args) {
                if ("--answer".equals(arg)) {
                    answerMode = true;
                } else if ("--verbose".equals(arg)) {
                    verbose = true;
                } else if (arg.startsWith("--only=")) {
                    only.addAll(Arrays.asList(arg.substring("--only=".length()).split(",")));
                } else if (arg.startsWith("--label=")) {
                    label = arg.substring("--label=".length());
                } else if (arg.startsWith("--compare=")) {
                    compare = Path.of(arg.substring("--compare=".length()));
                } else {
                    /*
                     * 나머지는 스프링에 그대로 넘긴다 — 이 기계의 DB 접속값이 `.env`가 아니라
                     * IDE 실행 구성에 있을 수 있어(`CLAUDE.local.md`) 한 줄로 덮어쓸 자리가
                     * 필요하다. 인자 이름을 따로 만들면 스프링 프로퍼티와 두 벌이 된다.
                     */
                    springArgs.add(arg);
                }
            }
            String resolved =
                    label != null
                            ? label
                            : (answerMode ? "answer" : "retrieval")
                                    + "-"
                                    + Instant.now().toString().replaceAll("[:.]", "-");
            return new Options(
                    answerMode,
                    verbose,
                    List.copyOf(only),
                    resolved,
                    Optional.ofNullable(compare),
                    List.copyOf(springArgs));
        }

        /** `--only=B` 는 군 접두어, `--only=follow-up` 은 태그, `--only=B1` 은 시나리오 하나 */
        boolean selected(Scenario scenario) {
            if (only.isEmpty()) {
                return true;
            }
            return only.stream()
                    .map(value -> value.strip().toLowerCase(Locale.ROOT))
                    .anyMatch(
                            value ->
                                    scenario.id().toLowerCase(Locale.ROOT).startsWith(value)
                                            || scenario.tag().equals(value));
        }
    }
}
