package org.sscc.ssccopsserver.domain.member.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberGradeHistoryEntity;

public interface MemberGradeHistoryRepository
        extends JpaRepository<MemberGradeHistoryEntity, Long> {}
