package org.sscc.ssccopsserver.domain.form.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

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
     * 문항 식별자 보호(#32 수정)의 판단 근거. 상태를 가리지 않고 한 건이라도 있으면 참이다 —
     * 임시저장(DRAFT) 응답의 rspns_cn도 key가 qitemId라, 제출 전이라고 해서 문항을 지워도
     * 되는 것은 아니다. 목록의 responseCount가 DRAFT를 빼는 것과는 판단 기준이 다르다.
     */
    boolean existsByForm(FormEntity form);

    /*
     * 폼별 응답 목록(#37). 운영자용 목록 표가 회원_명·학번·학과·등급·상태를 그리므로 회원과
     * 그 등급·상태 기준 코드까지 한 번에 끌어온다 — 응답마다 mbr을 따로 조회하면 그대로 N+1이고
     * (DB-13), 모집 폼은 응답이 수백 건이라 그 배수가 그대로 쿼리 수가 된다.
     *
     * 등급·상태까지 그래프에 넣는 것은 지연 로딩 프록시의 식별자 접근에 기대지 않기 위해서다.
     * mbr_grd_cd·mbr_stts_cd는 응답에 실을 값이 코드(= 식별자) 하나뿐이라 프록시를 초기화하지
     * 않고도 읽히는 것이 보통이지만, 그 최적화가 도는지 여부에 쿼리 수가 걸려 있으면 매핑을
     * 조금만 손대도 조용히 N+1로 되돌아간다.
     *
     * 상태 필터가 선택 사항이라 FormRepository와 같은 이유로 상태 집합을 받는다 (열거형
     * 파라미터에 NULL을 넣고 분기하면 Hibernate가 타입을 추론하지 못한다).
     *
     * 정렬은 '제출 일시 내림차순'이되 DRAFT는 sbmsn_dt가 NULL이므로 mdfcn_dt로 폴백한다 —
     * NULL을 그대로 정렬에 태우면 DB에 따라 맨 앞이나 맨 뒤로 몰려 '작성 중' 응답만 따로 볼 때
     * 최근 저장한 것이 어디 있는지 알 수 없다. 동시각 동률은 식별자로 끊어 페이지를 다시 열어도
     * 순서가 흔들리지 않게 한다 — 상세의 이전/다음 이동이 이 순서를 그대로 쓴다.
     *
     * 페이징을 두지 않는다. 근거는 FormResponseServiceImpl.getResponses 주석에 있다.
     */
    @EntityGraph(attributePaths = {"member", "member.membershipGrade", "member.membershipStatus"})
    @Query(
            "select r from FormResponseHistoryEntity r"
                    + " where r.form = :form and r.status in :statuses"
                    + " order by coalesce(r.submittedAt, r.updatedAt) desc, r.id desc")
    List<FormResponseHistoryEntity> findAllForOperatorList(
            @Param("form") FormEntity form, @Param("statuses") Collection<ResponseStatus> statuses);

    /*
     * 위 목록의 식별자만. 상세의 이전/다음 이동(#37)이 인접 응답을 고를 때 쓴다.
     *
     * 같은 조건·같은 정렬을 두 번 적는 대신 엔티티 목록을 그대로 다시 부르지 않는 것은, 상세
     * 화면 한 번에 폼의 모든 응답과 그 회원을 전부 적재하게 되기 때문이다. 이동에 필요한 것은
     * 앞뒤 식별자 두 개뿐이다.
     */
    @Query(
            "select r.id from FormResponseHistoryEntity r"
                    + " where r.form = :form and r.status in :statuses"
                    + " order by coalesce(r.submittedAt, r.updatedAt) desc, r.id desc")
    List<Long> findIdsForOperatorList(
            @Param("form") FormEntity form, @Param("statuses") Collection<ResponseStatus> statuses);

    /*
     * 폼별·상태별 응답 건수 일괄 집계 (#32 폼 목록 · #37 폼 상세의 응답 요약).
     *
     * 상태를 GROUP BY에 넣은 것은 #37에서다. 폼 상세가 전체·제출·승인·반려 네 숫자를 보여주는데,
     * 총합용 질의와 상태별 질의를 따로 두면 폼 목록이 폼마다 두 번씩 집계하게 되고 두 결과가
     * 어긋날 여지도 생긴다. 호출부가 필요한 만큼 접어 쓴다 — 목록은 합으로, 상세는 그대로.
     *
     * 어떤 상태를 셀지는 여전히 호출부가 정한다(임시저장 제외 여부가 갈린다).
     *
     * 폼 식별자를 직접 꺼내는 것은(f.id) 연관을 타면 프로젝션 이름이 form.id가 되어
     * getFormId()와 맞지 않기 때문이다.
     */
    @Query(
            "select f.id as formId, r.status as status, count(r) as responseCount"
                    + " from FormResponseHistoryEntity r join r.form f"
                    + " where f.id in :formIds and r.status in :statuses"
                    + " group by f.id, r.status")
    List<FormResponseCount> countByFormIds(
            @Param("formIds") Collection<Long> formIds,
            @Param("statuses") Collection<ResponseStatus> statuses);

    /*
     * 응답 단건 조회 (#37). **폼과 응답 식별자를 반드시 함께 건다.**
     *
     * 경로에 두 값이 다 있는데 응답 식별자만으로 조회하면 /v1/forms/1/responses/999가 다른 폼의
     * 응답을 그대로 돌려준다 — 지원자 답변과 개인정보가 폼 경계를 넘어 새어 나가는 데 그
     * 한 줄이면 충분하다. 없는 응답과 남의 폼 응답은 여기서 같은 빈 결과가 된다.
     */
    @EntityGraph(attributePaths = {"member", "member.membershipGrade", "member.membershipStatus"})
    Optional<FormResponseHistoryEntity> findByIdAndForm(Long id, FormEntity form);
}
