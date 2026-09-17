package org.sscc.ssccopsserver.domain.assistant.entity;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.sscc.ssccopsserver.domain.assistant.code.RagApplyStatus;
import org.sscc.ssccopsserver.domain.assistant.code.RagDocumentType;
import org.sscc.ssccopsserver.domain.assistant.code.RagIndexStatus;
import org.sscc.ssccopsserver.domain.assistant.code.error.AssistantErrorCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * rag_doc(RAG_문서) — 규정 도우미가 참조하는 문서의 판본 (#396 · ADR-0029).
 *
 * **행이 만들어지는 경로는 업로드 API 하나다**(#399). 레포 커밋도 Gradle 태스크도 적재 경로가
 * 아니다 — 옛 안(회칙을 md로 커밋하고 태스크로 적재)을 버린 이유는 «고칠 수 있는 사람이 배포할
 * 수 있는 사람뿐»이 이 기능을 무의미하게 만들기 때문이고(규정 개정은 총회에서 나온다), 그 태스크가
 * 개발자 노트북에서 운영 DB에 붙어 도는 경로라 누가 언제 무엇을 바꿨는지가 남지 않기 때문이다.
 * 그 «누가»가 아래 `registrant`이며 요청 본문이 아니라 인증 주체에서 온다(#78 규칙).
 *
 * **상태가 두 축이다** — 색인 진행(`indexStatus`)과 적용 여부(`applyStatus`). 한 컬럼에 겹치면
 * «색인은 끝났지만 아직 시행 전인 개정안»을 표현할 수 없는데 첫 업로드 대상이 바로 그것이다.
 * 검색 조건은 `INDEXED AND EFFECTIVE` 둘이며, 조회한 뒤 거르는 것이 아니라 검색 필터에 넣는다.
 *
 * **전이 검증은 서비스가 아니라 여기서 던진다.** 표 자체는 두 코드 enum이 갖고 이 클래스는
 * 그것을 묻고 거절만 한다 — 어느 쪽도 두 벌이 되지 않게 한 자리씩이다.
 *
 * **`del_dt`가 없다 — 삭제는 하드다**(ADR-0029). 폼(#329)·행사(#347)와 갈리는 것은 그쪽이
 * «치우기»이고 이쪽은 «잘못 올린 파일을 없었던 것으로 만들기»이기 때문이며, 남길 값이 있는 옛
 * 판본은 `SUPERSEDED`가 이미 맡는다. 그래서 되살리기도 없다.
 *
 * 원본 파일은 R2에 있고 참조는 `file_rfrnc`가 `trgt_se_cd = 'RAG_DOCUMENT'`로 갖는다 —
 * **재색인의 재료가 원본**이기 때문이다(청크에서 원문을 복원할 수 없다: 해설을 뺐고 overlap이
 * 겹치며 장 헤더를 덧붙였다).
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "rag_doc")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class RagDocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rag_doc_id")
    private Long id;

    /** 인용 카드에 찍히는 표시명. 기본값은 원본 파일명에서 확장자를 뗀 것이고 운영진이 고칠 수 있다 */
    @Column(name = "doc_nm", nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type_cd", nullable = false, length = 20, updatable = false)
    private RagDocumentType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "indx_stts_cd", nullable = false, length = 20)
    private RagIndexStatus indexStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "aplcn_stts_cd", nullable = false, length = 20)
    private RagApplyStatus applyStatus;

    /*
     * 색인 실패 사유. FAILED가 아니면 NULL이다.
     *
     * 워커는 요청 밖에서 돌아 돌려줄 응답이 없으므로 **이 컬럼이 오류 코드의 자리를 대신한다.**
     * 화면이 «재색인» 버튼과 함께 보여주는 것이 이 문장이다.
     */
    @Column(name = "fail_rsn_cn", columnDefinition = "text")
    private String failureReason;

    /*
     * 이 판본이 효력을 갖는 날. **DRAFT는 NULL이 정상이다** — 의결 전 개정안에는 발효일이 없고,
     * 실제로 첫 업로드 대상인 회칙 개정안의 부칙이 발효일을 «개강총회 의결일»로 비워 두고 있다.
     * 답변의 «YYYY-MM-DD 시행 기준» 배지가 이 값이다.
     */
    @Column(name = "enfc_bgng_ymd")
    private LocalDate effectiveFrom;

    /** 올린 파일의 이름. 표시명(`name`)과 달리 바뀌지 않는다 */
    @Column(name = "orgnl_file_nm", nullable = false, length = 200, updatable = false)
    private String originalFileName;

    /** 바이트. 상한 10MB는 도메인이 끊는다 — 서블릿 계층은 더 높게 둬 도메인 오류 코드가 붙게 한다(#84와 같은 두 겹) */
    @Column(name = "file_sz", nullable = false, updatable = false)
    private Integer fileSize;

    /** 색인 완료 시점에 채운다. 그 전에는 NULL(화면의 «—»)이며 활성 청크 총량 상한 판정의 재료다 */
    @Column(name = "chunk_cnt")
    private Integer chunkCount;

    /** 워커가 집은 시각. **실측 없이 배치 크기를 조정할 수 없다**(기획안 §12.4) */
    @Column(name = "indx_bgng_dt")
    private Instant indexStartedAt;

    /** 색인이 끝나거나 실패한 시각 */
    @Column(name = "indx_end_dt")
    private Instant indexEndedAt;

    /*
     * 올린 회원. 요청 본문이 아니라 인증 주체에서 온다(#78) — 받아 주면 «누가 바꿨는가»를 스스로
     * 적어 넣을 수 있어 기록이 증거가 되지 못한다.
     *
     * **행위자 참조라 회원 하드 삭제를 막는다**(#361 · V9는 본인 데이터 FK에만 cascade를 걸었다).
     * 그 409의 문구와 미리보기의 blockedBy는 `MemberReferenceConstraints`가 준다.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rgtr_mbr_id", nullable = false, updatable = false)
    private MemberEntity registrant;

    @CreatedDate
    @Column(name = "crt_dt", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "mdfcn_dt", nullable = false)
    private Instant updatedAt;

    /**
     * 새 문서. <b>언제나 {@code PENDING} + {@code DRAFT}로 들어온다</b> — 업로드가 상태를 고르지 못하는 것이 «올린 것이 곧바로 답변의
     * 근거가 되지 않는다»의 전부다(ADR-0029 · 이 규칙은 판본 관리가 사라져도 그대로다).
     *
     * <p><b>판본 인자가 없다</b>(ADR-0034). 규정이 갱신되면 운영진이 옛 문서를 지우고 새 문서를 올리므로 문서 한 건이 곧 그 규정이다 — 묶을 것이 없어
     * 문서 식별자도 판본 번호도 받지 않는다.
     */
    public static RagDocumentEntity register(
            String name,
            RagDocumentType type,
            String originalFileName,
            int fileSize,
            MemberEntity registrant) {
        return new RagDocumentEntity(
                null,
                name,
                type,
                RagIndexStatus.PENDING,
                RagApplyStatus.DRAFT,
                null,
                null,
                originalFileName,
                fileSize,
                null,
                null,
                null,
                registrant,
                null,
                null);
    }

    /**
     * 워커가 집었다. {@code PENDING → INDEXING}.
     *
     * <p>이전 색인의 흔적(실패 사유·청크 수·끝난 시각)을 여기서 지운다. 남겨 두면 재색인 중인 문서가 «실패»로 보이고, 화면이 그 둘을 구별할 재료가 없다.
     */
    public void startIndexing(Instant now) {
        requireIndexTransition(RagIndexStatus.INDEXING);
        this.indexStatus = RagIndexStatus.INDEXING;
        this.failureReason = null;
        this.chunkCount = null;
        this.indexStartedAt = now;
        this.indexEndedAt = null;
    }

    /** 색인 완료. {@code INDEXING → INDEXED}이며 이때 비로소 청크 수가 채워진다 */
    public void completeIndexing(int chunkCount, Instant now) {
        requireIndexTransition(RagIndexStatus.INDEXED);
        this.indexStatus = RagIndexStatus.INDEXED;
        this.chunkCount = chunkCount;
        this.failureReason = null;
        this.indexEndedAt = now;
    }

    /** 색인 실패. {@code INDEXING → FAILED}. <b>자동 재시도는 없다</b> — 화면의 «재색인»이 사람의 판단을 거친 재시도다 */
    public void failIndexing(String reason, Instant now) {
        requireIndexTransition(RagIndexStatus.FAILED);
        this.indexStatus = RagIndexStatus.FAILED;
        this.failureReason = reason;
        this.indexEndedAt = now;
    }

    /**
     * 다시 줄을 세운다. 화면의 «재색인»({@code INDEXED}·{@code FAILED} → {@code PENDING})과 기동 복구({@code
     * INDEXING} → {@code PENDING}, 기획안 §12.4)가 같은 메서드다 — 결과가 같고, 나누면 복구 경로만 흔적을 남기지 않는 규칙이 생긴다.
     *
     * <p>청크는 여기서 지우지 않는다. <b>재색인은 새 청크를 넣기 <i>직전에</i> 옛 청크를 지운다</b>(#400) — 순서를 뒤집으면 중간에 실패했을 때 그
     * 문서가 통째로 검색에서 사라진다.
     */
    public void requeueIndexing() {
        requireIndexTransition(RagIndexStatus.PENDING);
        this.indexStatus = RagIndexStatus.PENDING;
        this.failureReason = null;
        this.indexStartedAt = null;
        this.indexEndedAt = null;
    }

    /**
     * 적용 상태를 바꾼다 — 화면의 {@code PATCH …/apply-status}가 들어오는 문 (#401).
     *
     * <p><b>어느 값으로 불러도 받고 거절은 전이표가 한다.</b> 요청이 고른 값을 서비스가 먼저 걸러 내면 표가 두 벌이 되고, «되돌리는 길이 없다»는 사실이
     * 그때부터 두 곳에 적히기 시작한다 — {@code SUPERSEDED}가 종착점인 것도 {@code DRAFT}로 내려올 수 없는 것도 {@link
     * RagApplyStatus} 한 곳이 말한다.
     *
     * <p><b>다른 문서를 함께 내리지 않는다</b>(ADR-0034). 예전에는 같은 문서 식별자의 기존 시행본을 서비스가 잠그고 내린 뒤 이것을 불렀는데, 판본 관리를
     * 걷어내며 그 경로가 사라졌다 — 시행 중인 문서는 여러 건일 수 있고, 갱신된 규정의 옛 문서를 지우는 것은 운영진의 몫이다.
     *
     * @param effectiveFrom {@code EFFECTIVE}로 올릴 때만 쓰인다. 나머지 전이에서는 무시된다 — 시행일은 «시행 중이 된 판본»의 값이고,
     *     내려간 판본의 것은 «언제부터 언제까지 유효했나»로 그대로 남는다
     */
    public void changeApplyStatus(RagApplyStatus next, LocalDate effectiveFrom) {
        switch (next) {
            case EFFECTIVE -> makeEffective(effectiveFrom);
            case SUPERSEDED -> supersede();
                // 어디에서도 갈 수 없다 — 그 사실도 전이표가 말하게 둔다(여기서 따로 던지지 않는다)
            case DRAFT -> requireApplyTransition(RagApplyStatus.DRAFT);
        }
    }

    /**
     * 시행 중으로 올린다. {@code DRAFT → EFFECTIVE}.
     *
     * <p><b>색인이 끝나 있어야 한다</b>(409) — 아니면 «시행 중인데 검색되지 않는 문서»가 되어 도우미가 근거 없이 침묵한다.
     *
     * <p><b>이미 시행 중인 다른 문서가 있는지는 보지 않는다 — 여러 건이어도 된다</b>(ADR-0034). 예전에는 같은 문서 식별자당 한 벌이라 전환하는 쪽이
     * 기존 판본을 내렸고 부분 유니크 인덱스가 최종 방어선이었는데, 둘 다 사라졌다. «같은 규정의 두 문서가 함께 시행 중»을 막는 것은 이제 코드가 아니라
     * 운영이다(ADR-0034의 «포기하는 것»).
     */
    public void makeEffective(LocalDate effectiveFrom) {
        if (indexStatus != RagIndexStatus.INDEXED) {
            throw new GeneralException(AssistantErrorCode.RAG_DOCUMENT_NOT_INDEXED);
        }
        requireApplyTransition(RagApplyStatus.EFFECTIVE);
        this.applyStatus = RagApplyStatus.EFFECTIVE;
        this.effectiveFrom = effectiveFrom;
    }

    /** 옛 판본으로 내린다. {@code EFFECTIVE → SUPERSEDED}. 시행일은 지우지 않는다 — «언제부터 언제까지 유효했나»가 그 값이다 */
    public void supersede() {
        requireApplyTransition(RagApplyStatus.SUPERSEDED);
        this.applyStatus = RagApplyStatus.SUPERSEDED;
    }

    /** 검색이 이 판본을 보는가. 두 축을 함께 보는 유일한 자리이며 검색 필터가 같은 조건을 질의에 넣는다 */
    public boolean isSearchable() {
        return indexStatus == RagIndexStatus.INDEXED && applyStatus == RagApplyStatus.EFFECTIVE;
    }

    private void requireIndexTransition(RagIndexStatus next) {
        if (!indexStatus.canTransitionTo(next)) {
            throw new GeneralException(
                    AssistantErrorCode.INVALID_RAG_INDEX_STATUS_TRANSITION,
                    "색인 상태를 %s에서 %s로 바꿀 수 없습니다.".formatted(indexStatus, next));
        }
    }

    private void requireApplyTransition(RagApplyStatus next) {
        if (!applyStatus.canTransitionTo(next)) {
            throw new GeneralException(
                    AssistantErrorCode.INVALID_RAG_APPLY_STATUS_TRANSITION,
                    "적용 상태를 %s에서 %s로 바꿀 수 없습니다.".formatted(applyStatus, next));
        }
    }
}
