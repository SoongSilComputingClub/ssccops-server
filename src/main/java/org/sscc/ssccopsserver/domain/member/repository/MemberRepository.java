package org.sscc.ssccopsserver.domain.member.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

public interface MemberRepository
        extends JpaRepository<MemberEntity, Long>, MemberRepositoryCustom {

    Optional<MemberEntity> findByAuthUserId(UUID authUserId);

    boolean existsByAuthUserId(UUID authUserId);

    /*
     * 학번 선점 여부. 이관 회원(auth_user_id가 NULL인 행)도 포함해서 본다 — 학번이 일치한다는
     * 것만으로 그 행에 연결해 주면 학번만 아는 사람이 남의 계정을 가로챌 수 있어, 지금은
     * 자동 연결 대신 거절한다 (#21).
     */
    boolean existsByStudentNumber(String studentNumber);

    /*
     * 넘긴 학번 중 이미 mbr에 있는 것만 골라 돌려준다 (#84 CSV 이관 검증).
     *
     * existsByStudentNumber를 행마다 부르지 않는 것이 요점이다 — 128건짜리 명부면 조회가 128번이
     * 되고, 그 값들은 한 트랜잭션 안에서 변하지도 않는다. 학번 전체를 한 번에 모아 묻는다.
     *
     * 회원 자체가 아니라 학번 문자열만 가져오는 것은 중복 판정에 필요한 것이 존재 여부뿐이기
     * 때문이다. 이미 있는 회원의 이름·연락처를 응답에 싣지 않는다(BR-M40 — 자동 병합은 없고,
     * 어느 회원과 겹치는지는 검증 응답이 알려 줄 일이 아니다).
     */
    @Query("select m.studentNumber from MemberEntity m where m.studentNumber in :studentNumbers")
    List<String> findStudentNumbersIn(@Param("studentNumbers") Collection<String> studentNumbers);

    /*
     * 이관 회원 계정 연결의 후보 (#86). **auth_user_id가 비어 있는 회원으로 좁힌다** — 이미
     * 계정이 붙은 행은 연결 대상이 아니고, 여기서 걸러야 "남의 계정에 연결한다"는 상태가
     * 조회 단계에서 아예 손에 들어오지 않는다.
     *
     * 학번으로만 좁히고 회원명·연락처는 걸지 않는다. 정규화한 뒤에 비교해야 하는데(하이픈 유무·
     * 앞뒤 공백) 그 규칙을 SQL에 적으면 MemberLinkPolicy와 두 벌이 되고, DB 함수마다 표기가
     * 달라 H2와 PostgreSQL에서 다른 결과가 나올 자리가 생긴다. 좁히는 일만 DB가 하고 판정은
     * 정책 한 곳에서 한다.
     *
     * uk_mbr_student_number 때문에 결과는 사실상 0건 아니면 1건이지만 목록으로 받는다 —
     * '정확히 한 건일 때만 연결한다'는 규칙을 호출부가 세는 것이지 제약에 기대는 것이 아니다.
     */
    @Query(
            "select m from MemberEntity m where m.studentNumber = :studentNumber and m.authUserId"
                    + " is null")
    List<MemberEntity> findLinkCandidatesByStudentNumber(
            @Param("studentNumber") String studentNumber);

    /*
     * 같은 학번으로 **이미 계정이 붙어 있는** 회원 (#86).
     *
     * 위 후보 조회에서 아무것도 찾지 못했을 때만, 그리고 409 MEMBER_ALREADY_LINKED와 404
     * MEMBER_LINK_FAILED를 가르기 위해서만 부른다. 두 질의를 하나로 합치지 않는 것은 연결
     * 대상 후보가 'auth_user_id IS NULL'이라는 사실을 조회 자체에 남겨 두기 위해서다.
     */
    @Query(
            "select m from MemberEntity m where m.studentNumber = :studentNumber and m.authUserId"
                    + " is not null")
    List<MemberEntity> findLinkedByStudentNumber(@Param("studentNumber") String studentNumber);

    /*
     * 제외할 상태 코드를 파라미터로 받는다. 어떤 상태를 배정에서 뺄지는 조회 조건이 아니라
     * 회원 도메인의 정책이라, 저장소에 박아 두지 않고 서비스가 넘기게 했다.
     * 파생 쿼리로 쓰면 메서드명에 상태 프로퍼티 경로가 그대로 드러나 길어져 JPQL로 적는다.
     */
    @Query(
            "select m from MemberEntity m where m.id = :memberId and m.membershipStatus.code not in"
                    + " :excludedStatusCodes")
    Optional<MemberEntity> findAssignableById(
            @Param("memberId") Long memberId,
            @Param("excludedStatusCodes") Collection<String> excludedStatusCodes);

    /*
     * 담당자 후보 목록 (#76). 단건판(findAssignableById)과 **같은 제외 규칙**을 쓰며 서비스가
     * 같은 상수를 넘긴다 — 규칙을 두 벌로 두면 "업무 등록에서는 고를 수 있는데 목록에는 없는
     * 회원"이 생긴다.
     *
     * 등급을 함께 끌어온다. 응답이 등급 코드와 명칭을 내리므로 지연 로딩 그대로 두면 회원
     * 수만큼 쿼리가 더 나간다 (DB-13). 상태는 이 응답에 실리지 않으니 끌어오지 않는다.
     *
     * 정렬은 이름 오름차순이다 — 선택 칩의 드롭다운이라 사람을 눈으로 찾는 목록이고,
     * 대표 역할 여부(rprs_role_yn)는 표시용이라 정렬에 쓰지 않는다 (BR-M26).
     */
    @EntityGraph(attributePaths = "membershipGrade")
    @Query(
            "select m from MemberEntity m where m.membershipStatus.code not in"
                    + " :excludedStatusCodes order by m.name asc, m.id asc")
    List<MemberEntity> findAllAssignable(
            @Param("excludedStatusCodes") Collection<String> excludedStatusCodes);

    /*
     * 회원 단건 조회 (#76). 등급·상태를 함께 끌어와 상세 응답 조립 중에 지연 로딩이 터지지
     * 않게 한다 — findById는 그 둘을 프록시로 남겨 두어 응답 DTO를 만들 때마다 쿼리가 붙는다.
     */
    @EntityGraph(attributePaths = {"membershipGrade", "membershipStatus"})
    Optional<MemberEntity> findWithGradeAndStatusById(Long id);
}
