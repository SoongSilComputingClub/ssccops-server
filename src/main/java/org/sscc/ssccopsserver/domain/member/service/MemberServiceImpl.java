package org.sscc.ssccopsserver.domain.member.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
     * 현재는 회원 실재 여부만 본다. 탈퇴·제명 회원을 걸러내려면 mbr_stts 코드 체계가 필요한데
     * data.sql이 ENROLLED 하나만 시드하고 있어 판정 기준이 아직 없다.
     * 회원 관리 기능이 붙어 나머지 상태 코드가 채워지면 여기에 상태 조건을 추가한다.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<MemberEntity> findAssignableMember(Long memberId) {
        if (memberId == null) {
            return Optional.empty();
        }
        return memberRepository.findById(memberId);
    }
}
