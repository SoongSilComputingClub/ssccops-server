package org.sscc.ssccopsserver.domain.member.service;

import java.util.Optional;
import java.util.UUID;

import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

public interface MemberService {

    // Supabase 식별자로 회원을 조회하고, 없으면 임시회원으로 즉시 프로비저닝한다.
    MemberEntity findOrProvisionBySpbUserId(UUID spbUserId, String email);

    /*
     * 다른 도메인이 담당자·작성자 등으로 지정할 수 있는 회원인지 확인해 반환한다.
     * 지정 불가 사유를 어떤 오류로 볼지는 호출하는 도메인이 정하므로 예외 대신 Optional을 준다.
     */
    Optional<MemberEntity> findAssignableMember(Long memberId);
}
