package org.sscc.ssccopsserver.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.google.genai.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions.TaskType;

import com.google.genai.Client;
import com.google.genai.types.EmbedContentConfig;
import com.google.genai.types.EmbedContentResponse;
import com.google.genai.types.ListModelsConfig;
import com.google.genai.types.Model;

/*
 * Gemini 임베딩 진단 도구 (#395 · 규정 도우미 RAG · 로컬 전용).
 *
 * **이 클래스는 테스트 소스에만 있고 배포 아티팩트에 들어가지 않는다.** R2Check(#137)와 같은
 * 자리이며 이유도 같다 — 실제 자격증명이 있어야만 답이 나오는 진단이라 `./gradlew test` 에
 * 섞으면 키 없는 CI·기여자 로컬에서 언제나 건너뛰는 테스트가 된다.
 *
 * ssccops#322 가 768차원을 고른 근거(pgvector 인덱스 상한 2,000)는 **실제로 768이 나온다는
 * 전제 위에 있고, 그것이 이 이슈에서 유일하게 남아 있던 미지수였다.** 여기서 나오는 차원이
 * 곧 #396 `V10` 의 `vector(N)` 이므로, 3072이 나오면 되돌리는 비용은 전량 재적재다.
 *
 * 여섯 칸을 순서대로 확인한다:
 *   1. 설정        GEMINI_API_KEY 가 있는가
 *   2. 모델 목록    이 키로 쓸 수 있는 임베딩 모델과 **입력 토큰 상한** (= 잘림 임계값)
 *   3. 차원        요청 768 → 실제 길이 · 미지정 → 실제 길이 · 노름(정규화 여부)
 *   4. task-type   요청에 실리는가 — 같은 문장을 DOCUMENT/QUERY 로 임베딩해 벡터를 비교한다
 *   5. 잘림        긴 조문 + 센티널 — 벡터가 같으면 뒷부분이 통째로 버려진 것이다
 *   6. 채팅        모델 ID 한 줄이 실제로 답하는가
 *
 * 실행:
 *   ./gradlew geminiCheck
 *   ./gradlew geminiCheck -Pargs="--models=gemini-embedding-001,gemini-embedding-2"
 *   ./gradlew geminiCheck -Pargs="--file=private-workspace/rag/규정.md --chat-model=gemini-2.5-flash"
 *
 * 값은 환경변수에서 읽는다(.env 가 있으면 Gradle 이 주입한다):
 *   GEMINI_API_KEY · GEMINI_CHAT_MODEL · GEMINI_EMBEDDING_MODEL
 *
 * **무료 티어의 RPM·TPM·RPD 수치는 공개 문서에 없다**(공식 rate-limits 문서가 "AI Studio 에서
 * 확인하라"고만 한다). 이 도구는 그 값을 알아내지 못한다 — 콘솔에서 읽어 ssccops#324 에 적는다.
 */
public final class GeminiCheck {

    private static final String API_KEY_ENV = "GEMINI_API_KEY";

    /** 4단계·5단계가 쓰는 짧은 문장. 같은 문장을 조건만 바꿔 넣어 벡터를 비교한다 */
    private static final String PROBE_TEXT = "정회원은 총회에서 의결권을 가진다.";

    /** 5단계 센티널. 본문 뒤에 붙여 **버려지는지**를 본다 — 잘리면 앞부분만 남아 벡터가 원본과 같아진다(임베딩은 결정적이다). */
    private static final String TRUNCATION_SENTINEL =
            " 부칙 제99조(센티널) 이 문장은 진단용이며 회칙의 일부가 아니다. 잘리면 사라진다.";

    /** 기본 진단 대상. git 에 없는 디렉터리라 없으면 5단계를 건너뛴다 */
    private static final String DEFAULT_TEXT_FILE = "private-workspace/rag/회칙개정_2026_개정안전문.md";

    /** 요청할 차원. ssccops#322 가 정한 값이며 #396 `V10` 의 `vector(N)` 이 된다 */
    private static final int TARGET_DIMENSIONS = 768;

    private GeminiCheck() {}

    public static void main(String[] args) {
        List<String> argList = Arrays.asList(args);
        String apiKey = System.getenv(API_KEY_ENV);

        System.out.println("=".repeat(78));
        System.out.println("Gemini 임베딩 진단 (#395)");
        System.out.println("=".repeat(78));

        if (apiKey == null || apiKey.isBlank()) {
            System.out.println();
            System.out.println("[1/6] 설정 — 실패: " + API_KEY_ENV + " 가 비어 있습니다.");
            System.out.println();
            System.out.println("  Google AI Studio 에서 무료 키를 발급받아 .env 에 넣으세요:");
            System.out.println("    https://aistudio.google.com/apikey");
            System.out.println("    GEMINI_API_KEY=...");
            System.out.println();
            System.out.println("  GCP 프로젝트도 결제 계정도 필요 없습니다 — Vertex AI 가 아닙니다.");
            System.exit(1);
            return;
        }

        List<String> models =
                option(argList, "--models")
                        .map(value -> Arrays.asList(value.split(",")))
                        .orElseGet(
                                () ->
                                        List.of(
                                                envOrDefault(
                                                        "GEMINI_EMBEDDING_MODEL",
                                                        "gemini-embedding-001")));
        String chatModel =
                option(argList, "--chat-model")
                        .orElseGet(() -> envOrDefault("GEMINI_CHAT_MODEL", "gemini-2.5-flash"));
        Path textFile = Path.of(option(argList, "--file").orElse(DEFAULT_TEXT_FILE));

        System.out.println();
        System.out.println("[1/6] 설정 — OK");
        System.out.println("  키          " + mask(apiKey));
        System.out.println("  임베딩 모델  " + String.join(", ", models));
        System.out.println("  채팅 모델    " + chatModel);
        System.out.println(
                "  진단 문서    " + textFile + (Files.exists(textFile) ? "" : "  (없음 → 5단계 건너뜀)"));

        try (Client client = Client.builder().apiKey(apiKey).build()) {
            listEmbeddingModels(client);

            for (String model : models) {
                System.out.println();
                System.out.println("-".repeat(78));
                System.out.println("모델: " + model);
                System.out.println("-".repeat(78));
                checkDimensions(client, model);
                checkTaskType(client, apiKey, model);
                checkTruncation(client, model, textFile);
            }

            checkChat(client, chatModel);
        } catch (RuntimeException e) {
            System.out.println();
            System.out.println("  실패: " + e.getClass().getSimpleName() + " — " + e.getMessage());
            System.exit(1);
        }

        System.out.println();
        System.out.println("=".repeat(78));
        System.out.println("결과를 ssccops#322 에 적고, 무료 티어 RPM·TPM·RPD 는 AI Studio 콘솔에서");
        System.out.println("읽어 ssccops#324 에 적으세요 — 공개 문서에 그 수치가 없습니다.");
        System.out.println("=".repeat(78));
    }

    /* ── 2단계 ─────────────────────────────────────────────────────────── */

    /**
     * 이 키로 쓸 수 있는 임베딩 모델과 입력 토큰 상한.
     *
     * <p>모델 이름을 추측하지 않기 위한 칸이다 — `gemini-embedding-2`·`gemini-embedding-002` 처럼 문서마다 다르게 적힌 이름이
     * 실재하는지, 상한이 정말 2,048 인지 8,192 인지를 **키가 답하게** 한다. 그 상한이 곧 §5.3 조 단위 청킹의 임계값이다.
     */
    private static void listEmbeddingModels(Client client) {
        System.out.println();
        System.out.println("[2/6] 이 키로 쓸 수 있는 임베딩 모델");

        int found = 0;
        for (Model model : client.models.list(ListModelsConfig.builder().build())) {
            List<String> actions = model.supportedActions().orElseGet(List::of);
            if (!actions.contains("embedContent")) {
                continue;
            }
            found++;
            System.out.printf(
                    "  %-48s 입력 %s 토큰%n",
                    model.name().orElse("(이름 없음)"),
                    model.inputTokenLimit().map(String::valueOf).orElse("?"));
        }
        if (found == 0) {
            System.out.println("  (없음 — 목록 응답에 embedContent 지원 모델이 하나도 없습니다)");
        }
    }

    /* ── 3단계 ─────────────────────────────────────────────────────────── */

    /**
     * 요청한 차원이 실제로 나오는지, 그리고 그 벡터가 정규화돼 있는지.
     *
     * <p>노름이 1 이 아니어도 지금은 무해하다 — `spring.ai.vectorstore.pgvector.distance-type` 의 기본값이
     * `cosine-distance` 이고 코사인은 스케일 불변이기 때문이다. 이 칸의 쓸모는 **그 조건을 숫자로 남겨 두는 것**이다: 여기 1.0 이 아닌 값이 찍혀
     * 있으면 `euclidean`·`inner-product` 로 바꾸는 순간 검색이 조용히 망가진다.
     */
    private static void checkDimensions(Client client, String model) {
        System.out.println();
        System.out.println("[3/6] 차원");

        float[] shrunk = embed(client, model, PROBE_TEXT, TARGET_DIMENSIONS, null);
        System.out.printf(
                "  dimensions=%d 요청 → **%d** (노름 %.4f)%n",
                TARGET_DIMENSIONS, shrunk.length, norm(shrunk));
        if (shrunk.length != TARGET_DIMENSIONS) {
            System.out.println("  ⚠️ 요청한 차원이 나오지 않았습니다 — #396 의 vector(N) 을 이 값으로 잡아야 합니다.");
        }

        float[] natural = embed(client, model, PROBE_TEXT, null, null);
        System.out.printf("  dimensions 미지정 → %d (노름 %.4f)%n", natural.length, norm(natural));
        if (natural.length > 2000) {
            System.out.println("  (기본 출력이 pgvector 인덱스 상한 2,000 을 넘습니다 — 줄이는 것이 필수입니다)");
        }
    }

    /* ── 4단계 ─────────────────────────────────────────────────────────── */

    /**
     * task-type 이 실제로 요청에 실리는가.
     *
     * <p>같은 문장을 `RETRIEVAL_DOCUMENT` 와 `RETRIEVAL_QUERY` 로 임베딩해 비교한다. 임베딩은 결정적이므로 **벡터가 같으면 두 값 중
     * 어느 것도 요청에 실리지 않은 것**이다.
     *
     * <p>SDK 직접 호출과 Spring AI 모델 클래스를 나란히 재는 것이 이 칸의 요점이다 — 2026-09-13 바이트코드 확인 결과
     * `GoogleGenAiTextEmbeddingModel` 은 `EmbedContentConfig` 에 `outputDimensionality` 하나만 넣고
     * taskType 을 읽지 않는다. 「적재는 DOCUMENT, 질의는 QUERY」(#395)는 그 위에서는 성립하지 않으며, 성립하지 않는다는 사실이 **로그 어디에도
     * 남지 않는다.** 그래서 숫자로 확인한다.
     */
    private static void checkTaskType(Client client, String apiKey, String model) {
        System.out.println();
        System.out.println("[4/6] task-type 이 요청에 실리는가");

        float[] sdkDocument =
                embed(client, model, PROBE_TEXT, TARGET_DIMENSIONS, TaskType.RETRIEVAL_DOCUMENT);
        float[] sdkQuery =
                embed(client, model, PROBE_TEXT, TARGET_DIMENSIONS, TaskType.RETRIEVAL_QUERY);
        boolean sdkHonoursTaskType = !Arrays.equals(sdkDocument, sdkQuery);
        System.out.println(
                "  SDK 직접 호출        DOCUMENT ≠ QUERY : " + (sdkHonoursTaskType ? "예" : "아니오"));

        float[] springDocument = springEmbed(apiKey, model, TaskType.RETRIEVAL_DOCUMENT);
        float[] springQuery = springEmbed(apiKey, model, TaskType.RETRIEVAL_QUERY);
        boolean springHonoursTaskType = !Arrays.equals(springDocument, springQuery);
        System.out.println(
                "  Spring AI 1.1.8      DOCUMENT ≠ QUERY : "
                        + (springHonoursTaskType ? "예" : "아니오"));

        if (sdkHonoursTaskType && !springHonoursTaskType) {
            System.out.println();
            System.out.println("  ⚠️ **확진**: API 는 task-type 을 받지만 Spring AI 1.1.8 이 싣지 않습니다.");
            System.out.println("     application.yaml 의 task-type 은 지금 의도의 선언일 뿐이며,");
            System.out.println("     비대칭 임베딩(적재 DOCUMENT · 질의 QUERY)을 실제로 걸려면");
            System.out.println("     EmbeddingModel 을 우리가 감싸는 수밖에 없습니다(#395 후속).");
        } else if (springHonoursTaskType) {
            System.out.println();
            System.out.println("  Spring AI 가 task-type 을 싣습니다 — 질의 쪽만 옵션으로 덮어쓰면 됩니다.");
        }
    }

    /* ── 5단계 ─────────────────────────────────────────────────────────── */

    /**
     * 긴 조문이 조용히 잘리는가.
     *
     * <p>`gemini-embedding-001` 의 입력 상한은 2,048 토큰이고 한글은 대략 1토큰이 1~1.5자다. 조 단위 청크(§5.3)가 긴 조에서 거기
     * 닿으면 **오류 없이 뒷부분이 버려져** 그 부분이 영영 검색되지 않는다. 본문 뒤에 센티널을 붙여 벡터가 그대로인지 본다.
     */
    private static void checkTruncation(Client client, String model, Path textFile) {
        System.out.println();
        System.out.println("[5/6] 긴 조문이 잘리는가");

        if (!Files.exists(textFile)) {
            System.out.println("  건너뜀 — " + textFile + " 가 없습니다 (--file 로 지정할 수 있습니다).");
            return;
        }

        String longest;
        try {
            longest = longestArticle(Files.readString(textFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.out.println("  건너뜀 — 파일을 읽지 못했습니다: " + e.getMessage());
            return;
        }

        String head = longest.lines().findFirst().orElse("").strip();
        System.out.printf("  가장 긴 조: %s (%,d자)%n", head, longest.length());

        float[] plain = embed(client, model, longest, TARGET_DIMENSIONS, null);
        float[] withSentinel =
                embed(client, model, longest + TRUNCATION_SENTINEL, TARGET_DIMENSIONS, null);

        if (Arrays.equals(plain, withSentinel)) {
            System.out.println("  ⚠️ **잘립니다** — 뒤에 붙인 문장이 벡터를 바꾸지 못했습니다.");
            System.out.println("     조 단위 청크를 항 단위로 쪼개거나(§5.3) 입력 상한이 더 긴 모델을 쓰세요.");
        } else {
            System.out.println("  잘리지 않습니다 — 이 길이는 상한 안입니다.");
        }
    }

    /* ── 6단계 ─────────────────────────────────────────────────────────── */

    private static void checkChat(Client client, String chatModel) {
        System.out.println();
        System.out.println("[6/6] 채팅 — " + chatModel);
        String answer =
                client.models.generateContent(chatModel, "한 단어로만 답하세요: 대한민국의 수도는?", null).text();
        System.out.println("  응답: " + (answer == null ? "(없음)" : answer.strip()));
    }

    /* ── 도우미 ─────────────────────────────────────────────────────────── */

    /** SDK 직접 호출. taskType 이 null 이면 넣지 않는다(= API 기본값) */
    private static float[] embed(
            Client client, String model, String text, Integer dimensions, TaskType taskType) {
        EmbedContentConfig.Builder config = EmbedContentConfig.builder();
        if (dimensions != null) {
            config.outputDimensionality(dimensions);
        }
        if (taskType != null) {
            config.taskType(taskType.name());
        }

        EmbedContentResponse response =
                client.models.embedContent(model, List.of(text), config.build());
        List<Float> values =
                response.embeddings()
                        .filter(embeddings -> !embeddings.isEmpty())
                        .flatMap(embeddings -> embeddings.get(0).values())
                        .orElseThrow(() -> new IllegalStateException("임베딩이 비어 있습니다: " + model));

        float[] vector = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            vector[i] = values.get(i);
        }
        return vector;
    }

    /**
     * 운영 경로와 **같은 클래스**로 임베딩한다.
     *
     * <p>여기서 SDK 를 한 번 더 부르면 「진단은 통과했는데 운영 경로는 다르게 동작한다」가 가능해진다 — 확인해야 하는 것이 바로 이 클래스다(R2Check 가
     * R2Config 를 그대로 띄우는 것과 같은 이유).
     */
    private static float[] springEmbed(String apiKey, String model, TaskType taskType) {
        GoogleGenAiEmbeddingConnectionDetails connection =
                GoogleGenAiEmbeddingConnectionDetails.builder().apiKey(apiKey).build();
        GoogleGenAiTextEmbeddingOptions options =
                GoogleGenAiTextEmbeddingOptions.builder()
                        .model(model)
                        .dimensions(TARGET_DIMENSIONS)
                        .taskType(taskType)
                        .build();

        GoogleGenAiTextEmbeddingModel embeddingModel =
                new GoogleGenAiTextEmbeddingModel(connection, options);
        return embeddingModel
                .call(new EmbeddingRequest(List.of(PROBE_TEXT), options))
                .getResult()
                .getOutput();
    }

    /** `### 제N조` 로 시작하는 블록 중 가장 긴 것 */
    private static String longestArticle(String markdown) {
        List<String> articles = new ArrayList<>();
        StringBuilder current = null;

        for (String line : markdown.lines().toList()) {
            if (line.stripLeading().matches("^#{1,6}\\s*제\\d+조.*")) {
                if (current != null) {
                    articles.add(current.toString());
                }
                current = new StringBuilder();
            }
            if (current != null) {
                current.append(line).append('\n');
            }
        }
        if (current != null) {
            articles.add(current.toString());
        }

        return articles.stream()
                .max((a, b) -> Integer.compare(a.length(), b.length()))
                .orElse(markdown);
    }

    private static double norm(float[] vector) {
        double sum = 0;
        for (float value : vector) {
            sum += (double) value * value;
        }
        return Math.sqrt(sum);
    }

    private static Optional<String> option(List<String> args, String name) {
        return args.stream()
                .filter(arg -> arg.startsWith(name + "="))
                .map(arg -> arg.substring(name.length() + 1))
                .filter(value -> !value.isBlank())
                .findFirst();
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String mask(String apiKey) {
        return apiKey.length() <= 8
                ? "****"
                : apiKey.substring(0, 4) + "…" + apiKey.substring(apiKey.length() - 4);
    }
}
