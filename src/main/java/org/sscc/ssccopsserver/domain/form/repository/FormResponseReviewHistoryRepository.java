package org.sscc.ssccopsserver.domain.form.repository;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseReviewHistoryEntity;

/*
 * 폼 응답 검토 처리 이력 (#141).
 *
 * 조회 메서드가 하나뿐인 것은 별도 이력 엔드포인트를 두지 않기 때문이다 — 화면이 상세와 이력을
 * 언제나 함께 그리므로 응답 상세(FormResponseDetailResponse)가 타임라인을 함께 싣는다.
 *
 * 처리자(mbr)를 @EntityGraph로 함께 끌어오는 것은 이력 행마다 처리자_명을 그리기 때문이다.
 * 이름을 이력에 복사하지 않고 조인하는 대가가 N+1이면 복사하지 않기로 한 결정이 무너진다
 * (DB-13 · 응답 목록의 회원 조인과 같은 방식). 상세 화면의 쿼리 수는 폼 1 + 응답 1 + 인접
 * 식별자 1 + 이력 1로 네 번이며 이력이 몇 줄이든 그대로다 — 테스트가 못 박아 둔다.
 *
 * 정렬은 처리 일시 오름차순이다. 타임라인은 위에서 아래로 읽으므로 목록(제출 일시 내림차순)과
 * 방향이 반대이며, 같은 시각에 두 행이 들어오는 경우(재제출과 그 직후 처리)를 식별자로 끊어
 * 다시 열어도 순서가 흔들리지 않게 한다.
 */
public interface FormResponseReviewHistoryRepository
        extends JpaRepository<FormResponseReviewHistoryEntity, Long> {

    @EntityGraph(attributePaths = "processor")
    List<FormResponseReviewHistoryEntity> findAllByResponseOrderByProcessedAtAscIdAsc(
            FormResponseHistoryEntity response);
}
