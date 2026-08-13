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

@DataJpaTest
@ActiveProfiles("test")
class MemberServiceImplTest {

    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;

    private MemberService memberService() {
        return new MemberServiceImpl(
                memberRepository, memberGradeRepository, memberStatusRepository);
    }

    @Test
    void firstLoginProvisionsTemporaryMember() {
        UUID authUserId = UUID.randomUUID();

        MemberEntity created =
                memberService().findOrProvisionByAuthUserId(authUserId, "test@sscc.org");

        assertThat(created.getId()).isNotNull();
        assertThat(created.getAuthUserId()).isEqualTo(authUserId);
        assertThat(created.getEmail()).isEqualTo("test@sscc.org");
        assertThat(created.getName()).isEqualTo("test");
        assertThat(created.getMembershipGrade().getCode()).isEqualTo("TEMP");
        assertThat(created.getMembershipStatus().getCode()).isEqualTo("ENROLLED");
    }

    @Test
    void existingMappingDoesNotCreateDuplicate() {
        UUID authUserId = UUID.randomUUID();
        MemberService service = memberService();

        MemberEntity first = service.findOrProvisionByAuthUserId(authUserId, "test@sscc.org");
        MemberEntity second = service.findOrProvisionByAuthUserId(authUserId, "test@sscc.org");

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(memberRepository.count()).isEqualTo(1);
    }
}
