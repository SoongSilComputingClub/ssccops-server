package org.sscc.ssccopsserver.domain.assistant.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.sscc.ssccopsserver.domain.assistant.code.RagIndexStatus;
import org.sscc.ssccopsserver.domain.assistant.entity.RagDocumentEntity;

/*
 * 규정 문서 판본 조회 (#396).
 *
 * 이 표는 행이 수십 단위다(규정 문서 한 건씩 · ADR-0034). 그래서 페이징도, `indx_stts_cd` 인덱스도 두지
 * 않았고 — 근거는 `V10__create_assistant_tables.sql` 하단에 있다 — 목록은 전량을 정렬해 준다.
 */
public interface RagDocumentRepository extends JpaRepository<RagDocumentEntity, Long> {

    /**
     * 목록 — <b>전량을 최신 업로드 순으로</b> (#401 · 기획안 §13.2).
     *
     * <p>페이징이 없는 것은 이 표가 «문서 종류 × 판본»이라 행이 수십 단위이기 때문이다(클래스 주석 · {@code V10} 하단). 정렬이 식별자 내림차순인 것은
     * <b>방금 올린 행이 표 맨 위에 즉시 보여야</b> 하기 때문이다 — 업로드 응답이 201 + {@code PENDING}인 이유와 같은 자리이며(#399), 색인이
     * 끝나기를 기다리는 동안 운영진이 보는 것이 그 행이다.
     */
    List<RagDocumentEntity> findAllByOrderByIdDesc();

    /**
     * 문서명 부분 일치 — <b>검색은 클라이언트가 아니라 서버가 한다</b> (#401 · 기획안 §13.2).
     *
     * <p>클라이언트 필터링을 택하지 않은 것은 그것이 «목록을 통째로 내려받은 뒤»에만 성립하기 때문이다. 지금은 행이 수십 개라 둘 다 되지만, 그 조건이 깨지는 날
     * 화면과 서버를 함께 고쳐야 한다 — 여기서 거르면 문서가 몇 건이든 같은 코드다.
     *
     * <p>찾는 값이 {@code doc_cd}가 아니라 {@code doc_nm}인 것은 운영진이 화면에서 읽는 값이 표시명이기 때문이다. 대소문자를 가리지 않는다.
     */
    List<RagDocumentEntity> findAllByNameContainingIgnoreCaseOrderByIdDesc(String name);

    /**
     * <b>질의가 볼 수 있는 판본 — {@code INDEXED}이고 {@code EFFECTIVE}인 것</b> (#403 · 기획안 §5.5 · §6.1).
     *
     * <p><b>조건 둘이 이 메서드 하나에 박혀 있는 것이 요점이다.</b> 파라미터로 받지 않는 것은 부르는 쪽이 한쪽만 넘기는 순간 새어 나가기 때문이다 — 색인이
     * 끝나지 않은 판본을 보면 청크가 반쯤 든 문서로 답하고, 적용 상태를 빼면 <b>의결 전 개정안이 시행 중인 회칙 행세를 한다.</b> 후자는 사람의 자격을 판단하는
     * 근거가 조용히 바뀌는 자리다.
     *
     * <p><b>여기서 받은 식별자가 그대로 벡터 검색의 필터가 된다</b>({@code ragDocId in [...]}) — 조회한 뒤 {@code if}로 거르지
     * 않는다. 청크 메타데이터의 {@code applyStatus}로 거르지 않는 이유는 그 값이 <b>색인 시점의 값</b>이라서다: 적용 전환(#401)은 재색인을
     * 시키지 않으므로 {@code DRAFT → EFFECTIVE}로 올린 판본의 청크에는 아직 {@code DRAFT}가 찍혀 있다. 메타로 걸면 방금 시행 중으로 올린
     * 문서가 검색되지 않고, 그 고장은 «답이 달라지지 않는다»로만 드러난다.
     *
     * <p>정렬이 식별자 오름차순인 것은 인용의 판본 정보를 만들 때 순서가 흔들리지 않게 하기 위한 것뿐이다.
     */
    @Query(
            "select d from RagDocumentEntity d"
                    + " where d.indexStatus ="
                    + " org.sscc.ssccopsserver.domain.assistant.code.RagIndexStatus.INDEXED"
                    + " and d.applyStatus ="
                    + " org.sscc.ssccopsserver.domain.assistant.code.RagApplyStatus.EFFECTIVE"
                    + " order by d.id")
    List<RagDocumentEntity> findSearchable();

    /** 요약 3값 중 «색인 완료» (#401). 등록 문서 수는 {@code count()}이고 총 청크는 {@link #sumActiveChunkCount()}다 */
    long countByIndexStatus(RagIndexStatus indexStatus);

    /**
     * 그 회원이 {@code from} 이후에 올린 판본의 수 — <b>적재 레이트 리밋(회원당 일 10회)의 재료다</b>(#399 · 기획안 §11).
     *
     * <p><b>메모리 카운터가 아니라 행을 세는 것이 요점이다.</b> 세려는 것이 «요청 횟수»가 아니라 «이 사람이 오늘 색인 대기열에 얹은 문서»이고, 그 값은
     * 재기동해도 남아 있어야 한다 — 한도를 둔 이유가 그 하나하나가 나중에 임베딩을 수백 번 부르는 것이기 때문이다(1.2MB PDF 한 건이 184청크).
     *
     * <p>하드 삭제(ADR-0029)가 이 수를 줄인다 — 올렸다 지우면 그만큼 다시 올릴 수 있다. <b>그것이 맞다</b>: 지워진 문서는 색인되지 않아 쿼터를 태우지
     * 않고, 잘못 올린 파일을 지우고 고쳐 올리는 것이 이 화면의 정상 경로다.
     */
    long countByRegistrantIdAndCreatedAtGreaterThanEqual(Long registrantId, Instant from);

    /**
     * 색인 워커가 집을 줄 (#400)과 기동 복구가 되돌릴 줄(§12.4)을 같은 메서드로 찾는다.
     *
     * <p>정렬이 식별자 오름차순인 것은 <b>먼저 올린 문서가 먼저 색인된다</b>를 뜻한다 — 업로드 순서 말고 줄을 세울 근거가 없고, 무작위면 밀린 문서가 영영
     * 밀린다.
     */
    List<RagDocumentEntity> findAllByIndexStatusOrderByIdAsc(RagIndexStatus indexStatus);

    /**
     * 색인 워커가 집을 후보와 기동 복구가 되돌릴 후보 — <b>식별자만</b> (#400).
     *
     * <p>엔티티가 아니라 식별자인 것은 이 조회가 <b>잠그지 않기 때문</b>이다. 워커는 여기서 받은 식별자를 하나씩 {@link #findByIdForUpdate}로
     * 다시 읽어 상태를 확인한 뒤 전이한다 — 최초 가입자 부트스트랩(#71)의 «잠그고 다시 센다»와 같은 두 단계다. 엔티티를 미리 들고 다니면 잠금 대기가 풀린 뒤에도
     * 1차 캐시의 옛 상태를 보게 되어 그 재확인이 무의미해진다.
     *
     * <p>정렬이 식별자 오름차순인 것은 <b>먼저 올린 문서가 먼저 색인된다</b>를 뜻한다 — 업로드 순서 말고 줄을 세울 근거가 없고, 무작위면 밀린 문서가 영영
     * 밀린다.
     */
    @Query("select d.id from RagDocumentEntity d where d.indexStatus = :indexStatus order by d.id")
    List<Long> findIdsByIndexStatus(@Param("indexStatus") RagIndexStatus indexStatus);

    /**
     * 그 상태로 <b>N분 이상 머물러 있는</b> 판본 (#556 · ssccops#501).
     *
     * <p>부팅 복구가 쓰는 질의다. 위의 «상태로만» 찾는 질의를 그대로 쓰면 <b>지금 다른 인스턴스가 색인 중인 행까지 되돌린다</b> — Coolify 는 새
     * 컨테이너를 띄워 헬스체크를 통과시킨 뒤 옛 것을 내리므로 겹침은 사고가 아니라 배포 절차 그 자체이고, 배포는 {@code develop} 푸시마다 일어난다.
     *
     * <p>{@code indexStartedAt} 이 비어 있는 행은 <b>고르지 않는다.</b> 언제 집혔는지 모르는 행을 «오래됐다»고 볼 수 없고, 그런 행이 생기는
     * 경로는 전이와 시각 기록이 한 트랜잭션이라 사실상 없다 — 있다면 그것은 사람이 볼 일이다.
     */
    @Query(
            "select d.id from RagDocumentEntity d"
                    + " where d.indexStatus = :indexStatus"
                    + " and d.indexStartedAt is not null"
                    + " and d.indexStartedAt < :startedBefore"
                    + " order by d.id")
    List<Long> findIdsStuckInStatusSince(
            @Param("indexStatus") RagIndexStatus indexStatus,
            @Param("startedBefore") Instant startedBefore);

    /**
     * 그 판본을 <b>잠그고</b> 읽는다 — 색인 워커가 상태를 전이하기 직전에 부른다 (#400).
     *
     * <p>잠금이 «한 판본을 두 번 색인하지 않는다»의 자리다. 후보 조회(위)는 잠그지 않으므로 그 사이에 다른 경로가 같은 행을 집었거나(다중 인스턴스) 사람이 상태를
     * 바꿨을 수 있고, 그래서 <b>여기서 받은 엔티티의 상태를 다시 본 뒤에만</b> 전이한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from RagDocumentEntity d where d.id = :id")
    Optional<RagDocumentEntity> findByIdForUpdate(@Param("id") Long id);

    /**
     * <b>활성 청크 총량</b> — 색인이 끝났고 옛 판본이 아닌 문서들의 {@code chunk_cnt} 합 (#400 · 기획안 §8.2).
     *
     * <p>상한(3,000)을 판정하는 재료이며 «활성»의 정의가 여기 한 곳에 있다. {@code SUPERSEDED}와 삭제분을 세지 않는 것은 그때 청크가 실제로
     * 사라지기 때문이고(§5.6 · §12.4), 그래서 <b>3,000에 닿았다는 것은 실제로 문서가 늘었다는 뜻</b>이다. 지금 색인 중인 판본은 {@code
     * INDEXING}이라 자기 자신을 세지 않는다 — 재색인이 옛 청크 수를 이중으로 세지 않는 것이 그 덕이다.
     *
     * <p>행이 없으면 {@code sum}이 null이라 {@code Optional.empty()}다({@link #findMaxVersion}와 같은 모양).
     */
    @Query(
            "select sum(d.chunkCount) from RagDocumentEntity d"
                    + " where d.indexStatus ="
                    + " org.sscc.ssccopsserver.domain.assistant.code.RagIndexStatus.INDEXED"
                    + " and d.applyStatus <>"
                    + " org.sscc.ssccopsserver.domain.assistant.code.RagApplyStatus.SUPERSEDED")
    Optional<Long> sumActiveChunkCount();
}
