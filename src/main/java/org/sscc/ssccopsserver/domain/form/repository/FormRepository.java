package org.sscc.ssccopsserver.domain.form.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;

/*
 * 폼 조회. 컨트롤러·서비스는 후속 이슈(#32 폼 CRUD, #35 공개 폼 조회)에서 붙지만,
 * 그 이슈들이 기대는 조회 시그니처는 여기서 미리 잡아 둔다 — 나중에 각자 만들면
 * 같은 조회에 이름이 두 개 생기고 목록 정렬 기준이 화면마다 달라진다.
 */
public interface FormRepository extends JpaRepository<FormEntity, Long> {

    /*
     * 관리자 폼 목록(#32). 상태 필터가 선택 사항이라 상태 집합을 받는 형태로 두었다 —
     * "전체"는 전체 상태를 넣어 부르면 되고, 상태별 메서드를 상태 수만큼 늘리지 않아도 된다.
     *
     * 목록에는 생성자 이름이 필요하므로 연관을 함께 끌어온다. LAZY 그대로 두면 목록 한 줄마다
     * 회원 조회가 한 번씩 더 나간다 (DB-13).
     */
    @EntityGraph(attributePaths = "creator")
    Page<FormEntity> findAllByStatusIn(Collection<FormStatus> statuses, Pageable pageable);

    /*
     * 공개 폼 단건 조회(#35). 작성 중(DRAFT)인 폼은 링크를 알아도 열리면 안 되므로
     * 상태를 조건에 넣어 "없는 것"으로 만든다 — 존재를 알려주지 않기 위해 403으로 나누지 않는다.
     */
    Optional<FormEntity> findByIdAndStatus(Long id, FormStatus status);

    /*
     * 익명 미리보기용 단건 조회(ssccops#201). "접수를 연 적 있는" 폼만 찾는다 — 상태 집합을
     * 조건에 넣어 DRAFT는 findByIdAndStatus와 같은 태도로 "없는 것"이 된다. 호출부가 집합을
     * 넘기는 것은 목록(findAllByStatusIn)과 같은 이유이며, 무엇이 "연 적 있는" 상태인지는
     * PublicFormMetaServiceImpl 한 곳이 정한다.
     */
    Optional<FormEntity> findByIdAndStatusIn(Long id, Collection<FormStatus> statuses);

    /*
     * 시스템 폼 조회 (#140). **코드가 폼을 찾는 유일한 경로다.**
     *
     * form_id로 찾는 코드를 두지 않는 것이 이 메서드의 존재 이유다 — form_id는 IDENTITY라
     * 환경마다 다르고, 제목·라벨은 화면에서 바뀌는 운영 데이터다. 승인자 판정이 역할'명'을 보던
     * 동안 '총무'를 '재무'로 개명하는 것만으로 승인자가 사라진 일이 있었다(#118 → #123).
     *
     * sys_form_cd에 UNIQUE가 걸려 있어 결과는 최대 한 건이다. 첫 호출자는 기획안 시스템 폼
     * 시드(#173 ProposalFormSeeder)이며 "이미 세웠는가"를 이 조회 하나로 판정한다 — 제목이나
     * form_id로 물으면 제목을 고친 다음 기동에서 폼이 하나 더 생긴다.
     */
    Optional<FormEntity> findBySystemFormCode(String systemFormCode);

    /*
     * 라벨로 거른 폼 목록(#34). 관계 테이블을 지나는 조인이라 파생 쿼리로는 표현이 길어져
     * 연관 경로를 그대로 쓰는 파생 이름 대신 여기서 이름을 고정한다.
     */
    @EntityGraph(attributePaths = "creator")
    List<FormEntity> findAllByIdInOrderByIdDesc(Collection<Long> ids);

    /*
     * 관리자 폼 목록의 실제 조회 (#32 · GET /v1/forms). 상태·라벨 두 필터가 각각 선택이고
     * 둘 다 주면 AND라, 조합마다 파생 메서드를 두면 네 개가 된다. Specification을 쓰지 않은 것은
     * 필터가 두 개로 고정돼 있어 동적 조립의 이득이 없고, JPQL이면 조인·페치 전략이 한눈에
     * 보이기 때문이다.
     *
     * 상태는 집합으로 받아 "전체"를 전체 상태로 표현한다 — 열거형 파라미터에 NULL을 넣고
     * :status is null로 분기하면 Hibernate가 파라미터 타입을 추론하지 못해 방언에 따라 깨진다.
     * 반대로 라벨 식별자는 Long이라 NULL 비교가 안전해 그대로 선택 필터로 둔다.
     *
     * 라벨 필터를 조인이 아니라 EXISTS 하위 질의로 쓴 것은, 한 폼에 라벨이 여러 개 달려 있을 때
     * 조인이 폼을 라벨 수만큼 중복시키기 때문이다. distinct로 지우면 join fetch와 함께 쓸 때
     * 페이징이 메모리로 넘어간다. 생성자는 목록에 필요하므로 함께 페치한다 (DB-13).
     *
     * ── 접수 기간 비교 (#325 · ADR-0019) ──
     *
     * 목록 필터가 파생값(FormReceiptStatus) 축으로 옮겨져 질의가 기간을 함께 본다. periodMatch·now는
     * FormReceiptPolicy.filterFor가 만들며 **이 질의는 번역표를 갖지 않는다** — 판정식과 갈리지
     * 않게 하려고 번역은 판정식 옆 한 곳에서만 한다.
     *
     * periodMatch를 열거형이 아니라 String으로 받는 것은 위와 같은 이유다. 열거형 파라미터를 문자열
     * 리터럴과 비교하면 Hibernate가 타입을 추론하지 못한다. now는 언제나 값이 있으므로 ANY일 때도
     * 바인딩이 비지 않는다.
     *
     * AFTER_END가 시작 일시까지 보는 것은 receiptStatusOf가 SCHEDULED를 먼저 판정하기 때문이다.
     * 종료가 시작보다 앞선 폼은 FormEntity.requireValidReceiptPeriod가 막지만, 막는 자리와 판정하는
     * 자리가 달라 여기서 전제로 삼지 않는다 — 두 경로가 같은 답을 내는 것이 이 조건의 목적이다.
     *
     * 경계는 양쪽 모두 포함이다(<=·>=). NULL은 '제한 없음'이라 그 방향의 비교를 통과시킨다.
     *
     * ── 인덱스: 지금은 만들지 않는다 (실행 계획을 보고 내린 판단) ──
     *
     * form에는 **form_stts_cd 인덱스가 애초에 없다**(V1__baseline.sql에 PK와 FK뿐이다). 그래서
     * "상태 컬럼 하나로는 안 듣는다"가 아니라 원래 전부 훑고 있었고, 기간 비교가 붙어도 훑는
     * 것은 그대로다. PostgreSQL 16에 폼 5,000건(DRAFT 20% · CLOSED 20% · OPEN 60%, EXPIRED가
     * 1,234건)을 넣고 EXPLAIN (ANALYZE, BUFFERS)로 잰 값은 이렇다.
     *
     *   인덱스 없음                          Seq Scan · shared hit 55 · 1.6ms
     *   (form_stts_cd, rcpt_end_dt) 복합     Bitmap Index Scan · shared hit 41 · 1.2ms
     *
     * **만들지 않는 이유는 이득이 0.4ms라서가 아니라 선택도가 낮아서다.** EXPIRED가 전체의 25%라
     * 인덱스를 타도 힙 블록 38개를 그대로 읽고, 무엇보다 이 목록에는 페이징이 없어(위 주석)
     * 어차피 조건에 맞는 행 전부를 만들어야 한다. 응답 시간을 지배하는 것은 이 질의가 아니라
     * 뒤따르는 라벨·응답 집계 두 번이다. 마이그레이션 파일 하나를 늘려 얻는 것이 그만큼이면
     * 지금은 아니고, **폼이 만 단위로 늘거나 페이징이 붙으면 그때 이 표를 다시 뜬다.**
     *
     * 파라미터 OR 체인이 인덱스를 막지 않는다는 것도 함께 확인했다 — PREPARE/EXECUTE로 여섯 번
     * 돌려도 PostgreSQL이 custom plan을 유지해 :periodMatch를 상수로 접고 나머지 가지를 지운다.
     * 제네릭 플랜으로 굳어 네 가지를 다 훑는 계획이 나오지는 않았다.
     */
    @Query(
            "select f from FormEntity f join fetch f.creator"
                    + " where f.status in :statuses"
                    + " and (:labelId is null or exists ("
                    + "   select 1 from FormLabelRelationEntity r"
                    + "   where r.form = f and r.label.id = :labelId))"
                    + " and (:periodMatch = 'ANY'"
                    + "   or (:periodMatch = 'BEFORE_BEGIN'"
                    + "     and f.receiptBeginAt is not null and f.receiptBeginAt > :now)"
                    + "   or (:periodMatch = 'AFTER_END'"
                    + "     and (f.receiptBeginAt is null or f.receiptBeginAt <= :now)"
                    + "     and f.receiptEndAt is not null and f.receiptEndAt < :now)"
                    + "   or (:periodMatch = 'WITHIN'"
                    + "     and (f.receiptBeginAt is null or f.receiptBeginAt <= :now)"
                    + "     and (f.receiptEndAt is null or f.receiptEndAt >= :now)))"
                    + " order by f.id desc")
    List<FormEntity> findAllForAdminList(
            @Param("statuses") Collection<FormStatus> statuses,
            @Param("labelId") Long labelId,
            @Param("periodMatch") String periodMatch,
            @Param("now") Instant now);
}
