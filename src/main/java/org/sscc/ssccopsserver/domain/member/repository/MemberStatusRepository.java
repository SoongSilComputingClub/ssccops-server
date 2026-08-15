package org.sscc.ssccopsserver.domain.member.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberStatusEntity;

public interface MemberStatusRepository extends JpaRepository<MemberStatusEntity, String> {

    // 기준 코드 조회(GET /v1/member-statuses)의 순서. 규칙은 MemberGradeRepository와 같다
    List<MemberStatusEntity> findAllByOrderByDisplayOrderAscCodeAsc();
}
