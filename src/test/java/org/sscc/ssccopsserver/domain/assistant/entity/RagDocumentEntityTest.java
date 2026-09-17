package org.sscc.ssccopsserver.domain.assistant.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.sscc.ssccopsserver.domain.assistant.code.RagApplyStatus;
import org.sscc.ssccopsserver.domain.assistant.code.RagDocumentType;
import org.sscc.ssccopsserver.domain.assistant.code.RagIndexStatus;
import org.sscc.ssccopsserver.domain.assistant.code.error.AssistantErrorCode;
import org.sscc.ssccopsserver.global.apipayload.code.error.ErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/*
 * 판본의 상태 전이 (#396 · ADR-0029).
 *
 * **스프링 컨텍스트가 없다.** 전이 검증은 엔티티가 던지므로 순수 객체로 확인할 수 있고,
 * 그것이 규칙을 서비스가 아니라 여기 둔 이유 중 하나다.
 *
 * 이 클래스가 지키는 것은 **두 축이 갈라져 있다**는 사실이다 — 색인이 끝나기 전에 시행 중으로
 * 올라가면 «시행 중인데 검색되지 않는 문서»가 되어 도우미가 근거 없이 침묵한다.
 */
class RagDocumentEntityTest {

    private static final Instant NOW = Instant.parse("2026-09-14T03:00:00Z");

    /** 업로드가 상태를 고르지 못하는 것이 «올린 것이 곧바로 답변의 근거가 되지 않는다»의 전부다 */
    @Test
    void registersAsPendingDraft() {
        RagDocumentEntity document = document();

        assertThat(document.getIndexStatus()).isEqualTo(RagIndexStatus.PENDING);
        assertThat(document.getApplyStatus()).isEqualTo(RagApplyStatus.DRAFT);
        assertThat(document.getEffectiveFrom()).as("DRAFT는 시행일이 없는 것이 정상이다").isNull();
        assertThat(document.getChunkCount()).as("색인 전에는 «—»다").isNull();
        assertThat(document.isSearchable()).isFalse();
    }

    @Test
    void indexingRunsPendingToIndexed() {
        RagDocumentEntity document = document();

        document.startIndexing(NOW);
        assertThat(document.getIndexStatus()).isEqualTo(RagIndexStatus.INDEXING);
        assertThat(document.getIndexStartedAt()).isEqualTo(NOW);

        document.completeIndexing(37, NOW.plusSeconds(90));
        assertThat(document.getIndexStatus()).isEqualTo(RagIndexStatus.INDEXED);
        assertThat(document.getChunkCount()).isEqualTo(37);
        assertThat(document.getIndexEndedAt()).isEqualTo(NOW.plusSeconds(90));
    }

    /** 워커는 응답을 돌려줄 자리가 없다 — 사유가 그 자리를 대신하므로 행에 남아야 한다 */
    @Test
    void failureKeepsTheReasonOnTheRow() {
        RagDocumentEntity document = indexing();

        document.failIndexing("임베딩 쿼터 초과", NOW);

        assertThat(document.getIndexStatus()).isEqualTo(RagIndexStatus.FAILED);
        assertThat(document.getFailureReason()).isEqualTo("임베딩 쿼터 초과");
    }

    /*
     * 재색인이 실패의 흔적을 지우지 않으면 «색인 중인데 실패로 보이는» 행이 생기고, 화면은 그
     * 둘을 구별할 재료가 없다.
     */
    @Test
    void requeueAndRestartClearTheLastFailure() {
        RagDocumentEntity document = indexing();
        document.failIndexing("스캔 이미지라 본문이 없다", NOW);

        document.requeueIndexing();
        assertThat(document.getIndexStatus()).isEqualTo(RagIndexStatus.PENDING);
        assertThat(document.getFailureReason()).isNull();
        assertThat(document.getIndexStartedAt()).isNull();

        document.startIndexing(NOW.plusSeconds(600));
        assertThat(document.getFailureReason()).isNull();
        assertThat(document.getChunkCount()).isNull();
    }

    /** 기동 복구 — Free 플랜의 일시정지·배포 재시작으로 INDEXING에 멈춘 행을 되돌린다(§12.4) */
    @Test
    void interruptedIndexingGoesBackToPending() {
        RagDocumentEntity document = indexing();

        document.requeueIndexing();

        assertThat(document.getIndexStatus()).isEqualTo(RagIndexStatus.PENDING);
    }

    @Test
    void rejectsIndexTransitionsOutsideTheTable() {
        assertRejects(
                () -> document().completeIndexing(1, NOW),
                AssistantErrorCode.INVALID_RAG_INDEX_STATUS_TRANSITION);
        assertRejects(
                () -> document().failIndexing("사유", NOW),
                AssistantErrorCode.INVALID_RAG_INDEX_STATUS_TRANSITION);
        assertRejects(
                () -> document().requeueIndexing(),
                AssistantErrorCode.INVALID_RAG_INDEX_STATUS_TRANSITION);
    }

    /** 색인이 끝나야 시행 중으로 올라간다 — 두 축이 갈라져 있다는 사실이 이 한 줄에 걸려 있다 */
    @Test
    void onlyAnIndexedRevisionCanBecomeEffective() {
        assertRejects(
                () -> document().makeEffective(LocalDate.of(2026, 3, 2)),
                AssistantErrorCode.RAG_DOCUMENT_NOT_INDEXED);

        RagDocumentEntity indexed = indexed();
        indexed.makeEffective(LocalDate.of(2026, 3, 2));

        assertThat(indexed.getApplyStatus()).isEqualTo(RagApplyStatus.EFFECTIVE);
        assertThat(indexed.getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 3, 2));
        assertThat(indexed.isSearchable()).as("검색 조건은 INDEXED AND EFFECTIVE 둘이다").isTrue();
    }

    /** 내려간 판본은 더는 검색되지 않는다. 시행일은 지우지 않는다 — «언제까지 유효했나»가 그 값이다 */
    @Test
    void supersededRevisionIsNoLongerSearchableAndCannotComeBack() {
        RagDocumentEntity document = indexed();
        document.makeEffective(LocalDate.of(2026, 3, 2));

        document.supersede();

        assertThat(document.getApplyStatus()).isEqualTo(RagApplyStatus.SUPERSEDED);
        assertThat(document.getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 3, 2));
        assertThat(document.isSearchable()).isFalse();
        assertRejects(
                () -> document.makeEffective(LocalDate.of(2026, 9, 1)),
                AssistantErrorCode.INVALID_RAG_APPLY_STATUS_TRANSITION);
    }

    /*
     * 전환 API가 들어오는 문(#401)이 **어느 값으로 불려도 전이표에 물어본다.**
     *
     * 요청이 고른 값을 서비스가 먼저 걸러 내면 «되돌리는 길이 없다»가 두 곳에 적히기 시작한다 —
     * `DRAFT`로 내려오는 길이 없다는 사실도 여기 한 번만 적혀 있어야 한다.
     */
    @Test
    void applyStatusChangeAsksTheTransitionTableForEveryTarget() {
        RagDocumentEntity document = indexed();

        document.changeApplyStatus(RagApplyStatus.EFFECTIVE, LocalDate.of(2026, 3, 2));
        assertThat(document.getApplyStatus()).isEqualTo(RagApplyStatus.EFFECTIVE);
        assertThat(document.getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 3, 2));

        // 시행일은 EFFECTIVE로 올릴 때만 쓰인다 — 내려가도 «언제까지 유효했나»가 그대로 남는다
        document.changeApplyStatus(RagApplyStatus.SUPERSEDED, LocalDate.of(2026, 9, 1));
        assertThat(document.getApplyStatus()).isEqualTo(RagApplyStatus.SUPERSEDED);
        assertThat(document.getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 3, 2));

        assertRejects(
                () -> indexed().changeApplyStatus(RagApplyStatus.DRAFT, null),
                AssistantErrorCode.INVALID_RAG_APPLY_STATUS_TRANSITION);
    }

    private static void assertRejects(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(GeneralException.class)
                .extracting(thrown -> ((GeneralException) thrown).getErrorCode())
                .isEqualTo(expected);
    }

    private static RagDocumentEntity document() {
        return RagDocumentEntity.register(
                "회칙 개정안", RagDocumentType.STRUCTURED, "회칙개정_2026_개정안전문.md", 31_204, null);
    }

    private static RagDocumentEntity indexing() {
        RagDocumentEntity document = document();
        document.startIndexing(NOW);
        return document;
    }

    private static RagDocumentEntity indexed() {
        RagDocumentEntity document = indexing();
        document.completeIndexing(36, NOW.plusSeconds(60));
        return document;
    }
}
