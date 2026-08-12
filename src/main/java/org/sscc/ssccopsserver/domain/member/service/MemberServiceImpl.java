package org.sscc.ssccopsserver.domain.member.service;

import java.time.LocalDate;
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
    public MemberEntity findOrProvisionBySpbUserId(UUID spbUserId, String email) {
        return memberRepository
                .findBySpbUserId(spbUserId)
                .orElseGet(() -> provisionTemporaryMember(spbUserId, email));
    }

    // 최초 로그인 시 학번 등 실제 프로필 정보 없이 임시회원으로 즉시 가입시킨다 (JIT 프로비저닝).
    // stdnt_no/gen_no/mbr_nm 등 NOT NULL 컬럼은 플레이스홀더로 채우고, 정식 가입 절차에서 실제 값으로 갱신한다.
    private MemberEntity provisionTemporaryMember(UUID spbUserId, String email) {
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
                        placeholderStudentNumber(spbUserId),
                        0,
                        placeholderName(email),
                        null,
                        null,
                        null,
                        email,
                        temporaryGrade,
                        enrolledStatus,
                        LocalDate.now());
        member.assignSpbUserId(spbUserId);

        return memberRepository.save(member);
    }

    // stdnt_no는 VARCHAR(20) + UNIQUE라 UUID 원문은 못 담는다. 접두사 "T" + 대시 제거한 UUID 앞 19자리로 대체
    private String placeholderStudentNumber(UUID spbUserId) {
        return "T" + spbUserId.toString().replace("-", "").substring(0, 19);
    }

    private String placeholderName(String email) {
        if (email == null || !email.contains("@")) {
            return "임시회원";
        }
        return email.substring(0, email.indexOf('@'));
    }
}
