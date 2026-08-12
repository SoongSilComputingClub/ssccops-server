package org.sscc.ssccopsserver.domain.member.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleClassificationEntity;

public interface MemberRoleClassificationRepository
        extends JpaRepository<MemberRoleClassificationEntity, String> {}
