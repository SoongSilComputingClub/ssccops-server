package org.sscc.ssccopsserver.domain.form.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/*
 * 폼 응답 조회. 응답 제출(#35)·자동 저장(#36)·조회 및 상태 변경(#37)이 쓸 시그니처를 잡아 둔다.
 */
public interface FormResponseHistoryRepository
        extends JpaRepository<FormResponseHistoryEntity, Long> {

    /*
     * "내가 이 폼에 낸 응답". (form_id, mbr_id) UNIQUE 덕분에 반드시 0건 아니면 1건이라
     * List가 아니라 Optional로 받는다 — 자동 저장(#36)이 매번 이 조회로 이어 쓸 행을 찾는다.
     */
    Optional<FormResponseHistoryEntity> findByFormAndMember(FormEntity form, MemberEntity member);

    boolean existsByFormAndMember(FormEntity form, MemberEntity member);

    /*
     * 폼별 응답 목록(#37). 목록에 응답자 이름이 필요하므로 회원을 함께 끌어온다.
     * 상태 필터가 선택 사항이라 FormRepository와 같은 이유로 상태 집합을 받는다.
     */
    @EntityGraph(attributePaths = "member")
    Page<FormResponseHistoryEntity> findAllByFormAndStatusIn(
            FormEntity form, Collection<ResponseStatus> statuses, Pageable pageable);

    long countByFormAndStatus(FormEntity form, ResponseStatus status);

    /*
     * 폼 목록(#32)의 응답 건수 일괄 집계. 임시저장을 포함할지 갈리므로 셀 상태를 인자로 받는다.
     *
     * 폼 식별자를 직접 꺼내는 것은(f.id) 연관을 타면 프로젝션 이름이 form.id가 되어
     * getFormId()와 맞지 않기 때문이다.
     */
    @Query(
            "select f.id as formId, count(r) as responseCount"
                    + " from FormResponseHistoryEntity r join r.form f"
                    + " where f.id in :formIds and r.status in :statuses"
                    + " group by f.id")
    List<FormResponseCount> countByFormIds(
            @Param("formIds") Collection<Long> formIds,
            @Param("statuses") Collection<ResponseStatus> statuses);
}
