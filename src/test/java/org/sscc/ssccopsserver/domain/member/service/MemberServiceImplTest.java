package org.sscc.ssccopsserver.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.support.MemberFixture;

@DataJpaTest
@ActiveProfiles("test")
class MemberServiceImplTest {

    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;

    private MemberService memberService() {
        return new MemberServiceImpl(memberRepository);
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
}
