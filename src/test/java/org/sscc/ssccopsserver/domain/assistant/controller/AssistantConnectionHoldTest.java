package org.sscc.ssccopsserver.domain.assistant.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.sscc.ssccopsserver.domain.assistant.code.RagApplyStatus;
import org.sscc.ssccopsserver.domain.assistant.code.RagDocumentType;
import org.sscc.ssccopsserver.domain.assistant.entity.RagDocumentEntity;
import org.sscc.ssccopsserver.domain.assistant.repository.RagDocumentRepository;
import org.sscc.ssccopsserver.domain.assistant.service.RagChunkMetadata;
import org.sscc.ssccopsserver.domain.assistant.service.RagChunkStore;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.support.AssistantChatStubConfig;
import org.sscc.ssccopsserver.support.AssistantStubConfig;
import org.sscc.ssccopsserver.support.InMemoryRagChunkStore;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.StubChatModel;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

import com.zaxxer.hikari.HikariDataSource;

/*
 * **모델이 답을 만드는 동안 DB 커넥션을 쥐고 있지 않다** (#656 · ssccops#586).
 *
 * 질의 한 건은 시행 중인 문서를 DB에서 읽고(수 ms) Gemini를 두 번 부른다(수 초~수십 초). 서비스는
 * 트랜잭션을 앞의 조회 하나로 좁혀 두었는데(`AssistantServiceImpl` «트랜잭션이 모델 호출을 감싸지
 * 않는다»), **OSIV가 켜져 있던 동안에는 그것이 아무 일도 하지 않았다** — OSIV의 EntityManager가
 * 요청 안에서 처음 잡은 커넥션을 트랜잭션이 끝나도 응답 끝까지(SSE면 스트림이 닫힐 때까지) 쥐었다.
 * 그래서 이 테스트는 트랜잭션 경계가 아니라 **모델이 불리는 순간의 활성 커넥션 수**를 잰다.
 *
 * ⚠️ **테스트 트랜잭션을 걸 수 없다** — 걸면 테스트가 커넥션을 쥔 채 요청을 보내 모든 측정이 1
 * 이상이 된다. 그래서 전용 H2 DB에서 실제로 커밋하고(`RagIndexingWorkerTest`와 같은 이유) 픽스처를
 * 클래스당 한 번 세운다.
 *
 * 이 테스트가 깨지면 `spring.jpa.open-in-view`가 다시 켜졌는지부터 볼 것(`application.yaml`).
 */
@SpringBootTest(
        properties = {
            "ssccops.assistant.enabled=true",
            "spring.datasource.url="
                    + "jdbc:h2:mem:assistant-connection;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
        })
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
@Import({TestJwtDecoderConfig.class, AssistantStubConfig.class, AssistantChatStubConfig.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AssistantConnectionHoldTest {

    private static final String QUESTION = "{\"question\":\"정회원 승격 조건은?\"}";

    @Autowired private MockMvc mockMvc;
    @Autowired private DataSource dataSource;
    @Autowired private RagDocumentRepository ragDocumentRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private InMemoryRagChunkStore ragChunkStore;
    @Autowired private StubChatModel chatModel;

    private UUID memberToken;
    private Long ragDocId;

    /** 모델이 불린 순간의 활성 커넥션 수 — 불리지 않았으면 -1 */
    private final AtomicInteger activeWhileGenerating = new AtomicInteger(-1);

    /* 트랜잭션이 없어 픽스처가 DB에 남는다 — 클래스당 한 번만 세운다 */
    @BeforeAll
    void fixtures() {
        memberToken = UUID.randomUUID();
        var member =
                MemberFixture.save(
                        memberRepository,
                        memberGradeRepository,
                        memberStatusRepository,
                        memberToken,
                        "20260656",
                        "질문자",
                        "20260656@sscc.org");

        RagDocumentEntity document =
                RagDocumentEntity.register(
                        "SSCC 동아리 회칙", RagDocumentType.STRUCTURED, "회칙.md", 1024, member);
        document.startIndexing(Instant.now());
        document.completeIndexing(1, Instant.now());
        document.changeApplyStatus(RagApplyStatus.EFFECTIVE, LocalDate.of(2026, 3, 24));
        ragDocId = ragDocumentRepository.save(document).getId();
    }

    @BeforeEach
    void reset() {
        ragChunkStore.clear();
        chatModel.reset();
        ragChunkStore.add(List.of(articleChunk()));
        chatModel.answerWith("정회원 승격은 총회의 동의가 필요합니다. [1]");

        activeWhileGenerating.set(-1);
        chatModel.observeCalls(() -> activeWhileGenerating.set(activeConnections()));
    }

    /* 한 번에 받는 경로 — 생성이 요청 스레드에서 돈다 */
    @Test
    void doesNotHoldAConnectionWhileTheModelAnswers() throws Exception {
        mockMvc.perform(
                        post("/v1/assistant/queries")
                                .header("Authorization", "Bearer " + memberToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(QUESTION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answered").value(true));

        assertThat(activeWhileGenerating.get()).as("시행 중인 문서를 읽은 커넥션이 모델 호출 전에 반납됐어야 한다").isZero();
    }

    /*
     * 흘려보내는 경로 — OSIV가 켜져 있으면 비동기 완료까지 EntityManager를 연장하므로 **스트림이
     * 닫힐 때까지** 커넥션이 묶였다. 이쪽이 화면이 쓰는 경로다.
     */
    @Test
    void doesNotHoldAConnectionWhileTheAnswerStreams() throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/v1/assistant/queries/stream")
                                        .header("Authorization", "Bearer " + memberToken)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .accept(MediaType.TEXT_EVENT_STREAM)
                                        .content(QUESTION))
                        .andExpect(status().isOk())
                        .andReturn();
        awaitDone(result);

        assertThat(activeWhileGenerating.get()).as("스트림이 열려 있는 동안 커넥션을 쥐고 있으면 안 된다").isZero();
    }

    private int activeConnections() {
        try {
            return dataSource
                    .unwrap(HikariDataSource.class)
                    .getHikariPoolMXBean()
                    .getActiveConnections();
        } catch (SQLException exception) {
            throw new IllegalStateException("Hikari 풀을 찾지 못했다", exception);
        }
    }

    /** 구독이 다른 스레드에서 돌므로 `done`이 실릴 때까지 기다린다(`AssistantControllerTest.awaitStream`과 같다) */
    private static void awaitDone(MvcResult result) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            if (body.contains("event:done") && body.endsWith("\n\n")) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError(
                "스트림이 끝나지 않았다: " + result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private Document articleChunk() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(RagChunkStore.RAG_DOCUMENT_ID_KEY, ragDocId);
        metadata.put(RagChunkMetadata.DOC_TYPE, RagDocumentType.STRUCTURED.name());
        metadata.put(RagChunkMetadata.APPLY_STATUS, RagApplyStatus.EFFECTIVE.name());
        metadata.put(RagChunkMetadata.CHAPTER, "제2장 회원");
        metadata.put(RagChunkMetadata.SUPPLEMENTARY, false);
        metadata.put(RagChunkMetadata.ARTICLE_NUMBER, 7);
        metadata.put(RagChunkMetadata.ARTICLE_LABEL, "제7조");
        metadata.put(RagChunkMetadata.CITATION, "제7조 (회원의 구분)");
        return Document.builder()
                .text("제2장 회원 · 제7조 (회원의 구분)\n6항 정회원은 총회의 동의를 얻어 승격한다.")
                .metadata(metadata)
                .score(0.9)
                .build();
    }
}
