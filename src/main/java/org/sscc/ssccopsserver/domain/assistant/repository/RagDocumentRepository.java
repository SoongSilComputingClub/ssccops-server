package org.sscc.ssccopsserver.domain.assistant.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.sscc.ssccopsserver.domain.assistant.code.RagApplyStatus;
import org.sscc.ssccopsserver.domain.assistant.code.RagIndexStatus;
import org.sscc.ssccopsserver.domain.assistant.entity.RagDocumentEntity;

/*
 * 규정 문서 판본 조회 (#396).
 *
 * 이 표는 행이 수십 단위다(문서 종류 × 판본). 그래서 페이징도, `indx_stts_cd` 인덱스도 두지
 * 않았고 — 근거는 `V10__create_assistant_tables.sql` 하단에 있다 — 목록은 전량을 정렬해 준다.
 */
public interface RagDocumentRepository extends JpaRepository<RagDocumentEntity, Long> {

    /**
     * 같은 문서의 마지막 판본 번호. 새 판본은 <b>이 값 + 1</b>이며 {@code count}가 아니다 — 삭제가 하드라(ADR-0029) 행 수와 번호가 갈리고,
     * 세면 이미 쓴 번호를 다시 배정해 {@code uk_rag_doc_doc_cd_ver}에 걸린다. 없으면 {@link
     * RagDocumentEntity#FIRST_VERSION}.
     */
    @Query("select max(d.version) from RagDocumentEntity d where d.documentCode = :documentCode")
    Optional<Short> findMaxVersion(@Param("documentCode") String documentCode);

    /**
     * 같은 문서에서 그 적용 상태인 판본을 <b>잠그고</b> 읽는다. {@code EFFECTIVE}로 부르면 «지금 유효한 판본»이며, 적용 전환(#401)이 새 판본을
     * 올리기 전에 이것을 내린다.
     *
     * <p><b>잠그는 것이 규칙의 절반이다.</b> {@code doc_cd}당 시행본 하나는 부분 유니크 인덱스({@code uk_rag_doc_effective})와
     * 이 판정 두 겹인데, <b>H2에는 그 인덱스가 없어 여기가 유일한 방어선인 환경이 있다</b>(#143의 초안 1건 규칙과 같은 자리). 잠그지 않고 읽으면 동시
     * 요청 둘이 모두 «시행 중인 판본 없음»을 보고 둘 다 올라가며, 테스트는 그것을 재현하지 못한다.
     *
     * <p>없는 행은 잠글 수 없으므로 이 잠금이 «첫 시행본 두 건이 동시에»를 막지는 못한다 — 그쪽은 PostgreSQL의 인덱스가 받는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "select d from RagDocumentEntity d"
                    + " where d.documentCode = :documentCode and d.applyStatus = :applyStatus")
    Optional<RagDocumentEntity> findByDocumentCodeAndApplyStatusForUpdate(
            @Param("documentCode") String documentCode,
            @Param("applyStatus") RagApplyStatus applyStatus);

    /**
     * 색인 워커가 집을 줄 (#400)과 기동 복구가 되돌릴 줄(§12.4)을 같은 메서드로 찾는다.
     *
     * <p>정렬이 식별자 오름차순인 것은 <b>먼저 올린 문서가 먼저 색인된다</b>를 뜻한다 — 업로드 순서 말고 줄을 세울 근거가 없고, 무작위면 밀린 문서가 영영
     * 밀린다.
     */
    List<RagDocumentEntity> findAllByIndexStatusOrderByIdAsc(RagIndexStatus indexStatus);
}
