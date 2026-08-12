package org.sscc.ssccopsserver.domain.member.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

public interface MemberRepository extends JpaRepository<MemberEntity, Long> {}
