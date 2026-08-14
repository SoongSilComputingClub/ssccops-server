package org.sscc.ssccopsserver.domain.member.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.code.MemberStatusCode;
import org.sscc.ssccopsserver.domain.member.code.error.MemberErrorCode;
import org.sscc.ssccopsserver.domain.member.dto.MemberProfileResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberRoleResponse;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MemberServiceImpl implements MemberService {

    /*
     * 담당자로 지정할 수 없는 회원 상태. 탈퇴·제명은 조직을 떠난 사람이라 새 업무를 맡길 수 없다 —
     * 이력 보존을 위해 mbr 행 자체는 남기므로 "회원이 있다"만으로는 걸러지지 않는다.
     * 휴학·졸업은 회원 자격이 유지되므로 뺄 이유가 없다 (졸업생 인수인계·감사 업무가 실제로 있다).
     */
    private static final List<String> UNASSIGNABLE_STATUS_CODES =
            List.of(MemberStatusCode.WITHDRAWN.code(), MemberStatusCode.EXPELLED.code());

    private final MemberRepository memberRepository;
    private final MemberRoleAssignmentRepository memberRoleAssignmentRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<MemberEntity> findByAuthUserId(UUID authUserId) {
        if (authUserId == null) {
            return Optional.empty();
        }
        return memberRepository.findByAuthUserId(authUserId);
    }

    @Override
    @Transactional(readOnly = true)
    public MemberProfileResponse getProfile(Long memberId) {
        MemberEntity member =
                memberRepository
                        .findById(memberId)
                        .orElseThrow(() -> new GeneralException(MemberErrorCode.MEMBER_NOT_FOUND));

        List<MemberRoleResponse> roles =
                memberRoleAssignmentRepository.findCurrentByMemberId(memberId).stream()
                        .map(MemberRoleResponse::from)
                        .toList();

        return MemberProfileResponse.of(member, roles);
    }

    /*
     * 회원 실재 여부에 더해 배정 가능한 상태인지까지 본다 (UNASSIGNABLE_STATUS_CODES 참고).
     * 걸러진 회원은 빈 Optional로 돌아가고, 호출부(업무·하위 업무 등록)는 이를 "없는 담당자"와
     * 똑같이 다룬다 — 탈퇴 사실이 오류 메시지로 새어 나가지 않게 하려는 것이기도 하다.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<MemberEntity> findAssignableMember(Long memberId) {
        if (memberId == null) {
            return Optional.empty();
        }
        return memberRepository.findAssignableById(memberId, UNASSIGNABLE_STATUS_CODES);
    }
}
