package org.sscc.ssccopsserver.support;

import java.time.LocalDate;
import java.util.UUID;

import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberGradeEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberStatusEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;

/*
 * 테스트용 회원 생성 픽스처.
 *
 * 인증 컨버터가 더 이상 회원을 만들지 않으므로(가입 API에서만 생성한다) 회원이 필요한 테스트는
 * 여기서 직접 만든다. 등급·상태는 data.sql이 시드하는 TEMP/ENROLLED를 쓴다.
 */
public final class MemberFixture {

    private MemberFixture() {}

    public static MemberEntity save(
            MemberRepository memberRepository,
            MemberGradeRepository memberGradeRepository,
            MemberStatusRepository memberStatusRepository,
            UUID authUserId,
            String studentNumber,
            String name,
            String email) {

        MemberGradeEntity grade =
                memberGradeRepository.findById(MemberGradeEntity.TEMPORARY_CODE).orElseThrow();
        MemberStatusEntity status =
                memberStatusRepository.findById(MemberStatusEntity.ENROLLED_CODE).orElseThrow();

        MemberEntity member =
                MemberEntity.create(
                        studentNumber,
                        0,
                        name,
                        null,
                        null,
                        null,
                        email,
                        grade,
                        status,
                        LocalDate.now());
        if (authUserId != null) {
            member.assignAuthUserId(authUserId);
        }

        return memberRepository.save(member);
    }
}
