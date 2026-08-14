package org.sscc.ssccopsserver.domain.member.service;

import java.util.Optional;
import java.util.UUID;

import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

public interface MemberService {

    /*
     * Supabase 인증 사용자 식별자로 연결된 회원을 조회한다. 아직 가입하지 않았으면 비어 있다.
     * 인증 경로에서 호출되므로 여기서 회원을 생성하지 않는다 — 생성은 회원가입 API의 책임이다.
     */
    Optional<MemberEntity> findByAuthUserId(UUID authUserId);

    /*
     * 다른 도메인이 담당자·작성자 등으로 지정할 수 있는 회원인지 확인해 반환한다.
     * 지정 불가 사유를 어떤 오류로 볼지는 호출하는 도메인이 정하므로 예외 대신 Optional을 준다.
     */
    Optional<MemberEntity> findAssignableMember(Long memberId);
}
