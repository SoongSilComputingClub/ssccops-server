package org.sscc.ssccopsserver.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.sscc.ssccopsserver.domain.member.code.MemberStatusCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeHistoryRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusHistoryRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.support.MemberFixture;

@DataJpaTest
@ActiveProfiles("test")
class MemberServiceImplTest {

    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberGradeHistoryRepository memberGradeHistoryRepository;
    @Autowired private MemberStatusHistoryRepository memberStatusHistoryRepository;

    private MemberService memberService() {
        return new MemberServiceImpl(
                memberRepository,
                memberRoleAssignmentRepository,
                memberGradeRepository,
                memberStatusRepository,
                memberGradeHistoryRepository,
                memberStatusHistoryRepository,
                Clock.systemDefaultZone());
    }

    @Test
    void findsMemberLinkedToAuthUser() {
        UUID authUserId = UUID.randomUUID();
        MemberEntity saved = saveMember(authUserId);

        assertThat(memberService().findByAuthUserId(authUserId))
                .get()
                .extracting(MemberEntity::getId)
                .isEqualTo(saved.getId());
    }

    // 로그인은 했지만 아직 가입하지 않은 사용자 — 조회는 비어 있고, 회원이 생기지도 않는다
    @Test
    void lookupOfUnknownAuthUserIsEmptyAndCreatesNothing() {
        assertThat(memberService().findByAuthUserId(UUID.randomUUID())).isEmpty();
        assertThat(memberRepository.count()).isZero();
    }

    @Test
    void nullAuthUserIdIsEmpty() {
        assertThat(memberService().findByAuthUserId(null)).isEmpty();
    }

    @Test
    void enrolledMemberIsAssignable() {
        MemberEntity member = saveMember(MemberStatusCode.ENROLLED);

        assertThat(memberService().findAssignableMember(member.getId())).isPresent();
    }

    /*
     * 탈퇴·제명 회원의 mbr 행은 이력 보존을 위해 남아 있다. "회원이 존재한다"만 보면 통과해 버리므로
     * 행이 남아 있다는 사실까지 함께 확인한다 — 걸러진 이유가 상태 조건임을 못 박기 위해서다.
     */
    @Test
    void withdrawnOrExpelledMemberIsNotAssignable() {
        MemberEntity withdrawn = saveMember(MemberStatusCode.WITHDRAWN);
        MemberEntity expelled = saveMember(MemberStatusCode.EXPELLED);

        assertThat(memberRepository.findById(withdrawn.getId())).isPresent();
        assertThat(memberRepository.findById(expelled.getId())).isPresent();

        assertThat(memberService().findAssignableMember(withdrawn.getId())).isEmpty();
        assertThat(memberService().findAssignableMember(expelled.getId())).isEmpty();
    }

    // 휴학·졸업은 회원 자격이 유지되므로 배정에서 빠지지 않는다
    @Test
    void memberOnLeaveOrGraduatedStaysAssignable() {
        MemberEntity onLeave = saveMember(MemberStatusCode.MIL_LEAVE);
        MemberEntity graduated = saveMember(MemberStatusCode.GRADUATED);

        assertThat(memberService().findAssignableMember(onLeave.getId())).isPresent();
        assertThat(memberService().findAssignableMember(graduated.getId())).isPresent();
    }

    @Test
    void nullMemberIdIsNotAssignable() {
        assertThat(memberService().findAssignableMember(null)).isEmpty();
    }

    private MemberEntity saveMember(UUID authUserId) {
        return MemberFixture.save(
                memberRepository,
                memberGradeRepository,
                memberStatusRepository,
                authUserId,
                "20200001",
                "김도현",
                "test@sscc.org");
    }

    private MemberEntity saveMember(MemberStatusCode statusCode) {
        return MemberFixture.save(
                memberRepository,
                memberGradeRepository,
                memberStatusRepository,
                UUID.randomUUID(),
                "2020" + statusCode.ordinal() + "999",
                "김도현",
                "test@sscc.org",
                statusCode);
    }
}
