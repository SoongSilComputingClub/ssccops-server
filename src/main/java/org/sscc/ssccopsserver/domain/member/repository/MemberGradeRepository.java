package org.sscc.ssccopsserver.domain.member.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberGradeEntity;

public interface MemberGradeRepository extends JpaRepository<MemberGradeEntity, String> {

    /*
     * 기준 코드 조회(GET /v1/member-grades)가 쓰는 순서 (#76). 표시 순번(indct_seqno)은
     * 화면에 늘어놓을 차례를 담으라고 있는 컬럼이므로 정렬을 클라이언트에 맡기지 않는다.
     * 순번이 같은 코드가 있어도 응답 순서가 흔들리지 않게 코드로 동률을 끊는다.
     */
    List<MemberGradeEntity> findAllByOrderByDisplayOrderAscCodeAsc();
}
