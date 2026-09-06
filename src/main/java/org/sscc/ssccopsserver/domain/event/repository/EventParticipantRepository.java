package org.sscc.ssccopsserver.domain.event.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.event.entity.EventParticipantEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

public interface EventParticipantRepository extends JpaRepository<EventParticipantEntity, Long> {

    /*
     * 폼 연결 변경 가드(D11 · EVENT_FORM_IN_USE)의 판단 근거. 상태를 가리지 않는다 —
     * 취소(CANCELLED)된 참가자도 명단에 영구 보존되는 이력(D16)이라, 취소만 남았다고 연결을
     * 옮겨도 되는 것은 아니다.
     *
     * 행사 삭제 가드(D9)도 이것을 썼지만 삭제 자체가 없어졌다(ssccops ADR-0014).
     */
    boolean existsByEvent(EventEntity event);

    /*
     * 행사별 참가자 수 일괄 집계 (ssccops#139 목록·상세의 confirmedCount). 행사가 몇 건이든
     * 질의는 하나다 (DB-13 · FormResponseHistoryRepository.countByFormIds 선례).
     *
     * 어떤 상태를 셀지는 호출부가 정한다 — confirmedCount는 CONFIRMED만 센다. 대기·취소는
     * 정원 대비 확정 인원을 보는 값에 들어가면 화면의 "N/정원"이 부푼다.
     */
    @Query(
            "select e.id as eventId, count(p) as confirmedCount"
                    + " from EventParticipantEntity p join p.event e"
                    + " where e.id in :eventIds and p.status = :status"
                    + " group by e.id")
    List<EventParticipantCount> countByEventIds(
            @Param("eventIds") Collection<Long> eventIds,
            @Param("status") EventParticipantStatus status);

    /*
     * 명단 조회 (ssccops#146 · GET /v1/events/{eventId}/participants). 상태 필터는 집합으로
     * 받아 "전체"를 전체 상태로 표현한다 — 열거형 파라미터에 NULL을 넣고 :status is null로
     * 분기하면 Hibernate가 타입을 추론하지 못한다 (EventRepository·FormRepository 선례).
     *
     * 회원 정보는 명단 행에 복사하지 않고 mbr에서 조인한다(ResponseMemberSummary와 같은 태도).
     * 등급·상태까지 DTO가 쓰므로 @EntityGraph로 함께 끌어온다 — 없으면 명단 한 줄마다 조회가
     * 세 번씩 더 나간다 (DB-13). 신청 근거(formResponse)는 식별자만 쓰지만 LAZY 프록시에서
     * 꺼내도 조회가 나가므로 함께 페치한다.
     *
     * 정렬은 등록 순번(식별자 오름차순)이다. 대기 순번은 신청자에게 비공개지만(D5) 운영
     * 화면에는 신청 순서가 참고용으로 보여야 하고, 그 순서가 요청마다 흔들리면 참고가 되지 않는다.
     */
    @EntityGraph(
            attributePaths = {
                "member",
                "member.membershipGrade",
                "member.membershipStatus",
                "formResponse"
            })
    List<EventParticipantEntity> findAllByEventAndStatusInOrderByIdAsc(
            EventEntity event, Collection<EventParticipantStatus> statuses);

    /*
     * 행사 범위 검사 (ssccops#146). 참가자 식별자만으로 찾으면
     * /v1/events/1/participants/999가 다른 행사의 명단을 고친다 — 폼 응답의 findByIdAndForm과
     * 같은 자리이며, 없는 참가자와 남의 행사 참가자는 같은 404다.
     */
    Optional<EventParticipantEntity> findByIdAndEvent(Long id, EventEntity event);

    /*
     * 내 신청 목록의 참가 상태 (ssccops#145). 행사가 몇 건이든 질의는 하나다 — 신청마다
     * "명단에 있나"를 물으면 그대로 N+1이다 (DB-13 · countByEventIds와 같은 자리).
     *
     * UNIQUE(uk_event_ptcp_event_member) 덕에 (행사, 회원)당 최대 한 줄이라 호출부가 행사
     * 식별자로 바로 접을 수 있다.
     *
     * 상태를 가리지 않는다 — 취소(CANCELLED)된 신청도 신청자에게 보여야 할 결과이고, 명단은
     * 활동 이력으로 영구 보존된다(D16).
     */
    @Query(
            "select e.id as eventId, p.id as eventPtcpId, p.status as ptcpSttsCd"
                    + " from EventParticipantEntity p join p.event e"
                    + " where p.member = :member and e.id in :eventIds")
    List<MyApplicationParticipation> findAllByMemberAndEventIdIn(
            @Param("member") MemberEntity member, @Param("eventIds") Collection<Long> eventIds);

    /** 중복 등록 선조회. UNIQUE(uk_event_ptcp_event_member)가 최종 방어선이다 */
    boolean existsByEventAndMember(EventEntity event, MemberEntity member);

    /*
     * 이 회원의 명단 행 (#198 · 선발 다시 저장하기).
     *
     * 등록이 존재 여부만 묻는 것과 달리 행 자체가 필요하다 — 이미 명단에 있으면 새로 만드는
     * 대신 그 행의 상태를 맞춰야 하고, 그러려면 지금 상태를 알아야 한다. UNIQUE
     * (uk_event_ptcp_event_member) 덕에 (행사, 회원)당 최대 한 줄이라 Optional이 정확하다.
     */
    Optional<EventParticipantEntity> findByEventAndMember(EventEntity event, MemberEntity member);

    /** 정원 경고용 확정 인원. 목록 집계(countByEventIds)와 달리 행사 한 건만 본다 */
    long countByEventAndStatus(EventEntity event, EventParticipantStatus status);
}
