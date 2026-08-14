package org.sscc.ssccopsserver.domain.member.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.sscc.ssccopsserver.domain.member.dto.MemberProfileResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberRoleResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberSignupRequest;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.security.AuthenticatedUser;

public interface MemberService {

    /*
     * 회원가입. 인증만 마친 사용자를 정식 회원으로 만든다 — mbr 행이 생기는 유일한 경로다.
     *
     * 인증 주체에서 오는 값(auth_user_id·이메일)과 요청 본문에서 오는 값(프로필)을 나눠 받는다.
     * 응답은 세션 조회의 member 블록과 같은 MemberProfileResponse다 — 가입 직후 프론트가
     * 세션을 다시 조회하지 않아도 되게 하려는 것이 이 API의 설계 의도이기 때문이다.
     */
    MemberProfileResponse signUp(AuthenticatedUser user, MemberSignupRequest request);

    /*
     * Supabase 인증 사용자 식별자로 연결된 회원을 조회한다. 아직 가입하지 않았으면 비어 있다.
     * 인증 경로에서 호출되므로 여기서 회원을 생성하지 않는다 — 생성은 회원가입 API의 책임이다.
     */
    Optional<MemberEntity> findByAuthUserId(UUID authUserId);

    /*
     * 본인 회원 정보. 등급·상태·현재 역할이 모두 지연 로딩이라 조회 트랜잭션 안에서 DTO로 굳혀
     * 돌려준다 — 인증 주체에 실린 MemberEntity는 준영속이라 그대로 꺼내 쓸 수 없다.
     */
    MemberProfileResponse getProfile(Long memberId);

    /*
     * 회원이 지금 맡고 있는 조직 역할 목록. 종료일이 지난 배정은 담지 않는다.
     *
     * 운영 도메인의 승인자·운영진 판정(#47)이 쓴다. 다른 도메인은 회원 Repository를 직접
     * 호출할 수 없으므로(AR-07·LY-10) 역할 조회의 진입점을 여기 하나로 둔다.
     * 대표 역할 여부(rprs_role_yn)는 화면 표시용이며 권한 판정에 쓰지 않는다.
     */
    List<MemberRoleResponse> findCurrentRoles(Long memberId);

    /*
     * 다른 도메인이 담당자·작성자 등으로 지정할 수 있는 회원인지 확인해 반환한다.
     * 탈퇴·제명 회원은 존재하더라도 비어 있는 값으로 돌아간다.
     * 지정 불가 사유를 어떤 오류로 볼지는 호출하는 도메인이 정하므로 예외 대신 Optional을 준다.
     */
    Optional<MemberEntity> findAssignableMember(Long memberId);
}
