package org.sscc.ssccopsserver.domain.member.service;

import java.util.UUID;

import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

public interface MemberService {

    // Supabase 식별자로 회원을 조회하고, 없으면 임시회원으로 즉시 프로비저닝한다.
    MemberEntity findOrProvisionBySpbUserId(UUID spbUserId, String email);
}
