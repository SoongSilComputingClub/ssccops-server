package org.sscc.ssccopsserver.domain.member.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberGradeEntity;

public interface MemberGradeRepository extends JpaRepository<MemberGradeEntity, String> {}
