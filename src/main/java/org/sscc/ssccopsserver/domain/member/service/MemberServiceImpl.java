package org.sscc.ssccopsserver.domain.member.service;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberGradeEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberStatusEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.global.apipayload.code.error.CommonErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MemberServiceImpl implements MemberService {

    private final MemberRepository memberRepository;
    private final MemberGradeRepository memberGradeRepository;
    private final MemberStatusRepository memberStatusRepository;

    @Transactional
    public MemberEntity findOrProvisionByAuthUserId(UUID authUserId, String email) {
        return memberRepository
                .findByAuthUserId(authUserId)
                .orElseGet(() -> provisionTemporaryMember(authUserId, email));
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

    // 최초 로그인 시 학번 등 실제 프로필 정보 없이 임시회원으로 즉시 가입시킨다 (JIT 프로비저닝).
    // stdnt_no/gen_no/mbr_nm 등 NOT NULL 컬럼은 플레이스홀더로 채우고, 정식 가입 절차에서 실제 값으로 갱신한다.
    private MemberEntity provisionTemporaryMember(UUID authUserId, String email) {
        MemberGradeEntity temporaryGrade =
                memberGradeRepository
                        .findById(MemberGradeEntity.TEMPORARY_CODE)
                        .orElseThrow(
                                () -> new GeneralException(CommonErrorCode.INTERNAL_SERVER_ERROR));
        MemberStatusEntity enrolledStatus =
                memberStatusRepository
                        .findById(MemberStatusEntity.ENROLLED_CODE)
                        .orElseThrow(
                                () -> new GeneralException(CommonErrorCode.INTERNAL_SERVER_ERROR));

        MemberEntity member =
                MemberEntity.create(
                        placeholderStudentNumber(authUserId),
                        0,
                        placeholderName(email),
                        null,
                        null,
                        null,
                        email,
                        temporaryGrade,
                        enrolledStatus,
                        LocalDate.now());
        member.assignAuthUserId(authUserId);

        return memberRepository.save(member);
    }

    // stdnt_no는 VARCHAR(20) + UNIQUE라 UUID 원문은 못 담는다. 접두사 "T" + 대시 제거한 UUID 앞 19자리로 대체
    private String placeholderStudentNumber(UUID authUserId) {
        return "T" + authUserId.toString().replace("-", "").substring(0, 19);
    }

    private String placeholderName(String email) {
        if (email == null || !email.contains("@")) {
            return "임시회원";
        }
        return email.substring(0, email.indexOf('@'));
    }
}
