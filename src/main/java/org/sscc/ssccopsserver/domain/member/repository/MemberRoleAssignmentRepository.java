package org.sscc.ssccopsserver.domain.member.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleAssignmentEntity;

public interface MemberRoleAssignmentRepository
        extends JpaRepository<MemberRoleAssignmentEntity, Long> {}
