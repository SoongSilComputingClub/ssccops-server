package org.sscc.ssccopsserver.domain.member.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberStatusEntity;

public interface MemberStatusRepository extends JpaRepository<MemberStatusEntity, String> {}
