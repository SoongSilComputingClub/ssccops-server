package org.sscc.ssccopsserver.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.sscc.ssccopsserver.domain.member.code.MemberGradeCode;
import org.sscc.ssccopsserver.domain.member.code.MemberStatusCode;
import org.sscc.ssccopsserver.domain.member.code.error.MemberErrorCode;
import org.sscc.ssccopsserver.domain.member.dto.MemberLinkRequest;
import org.sscc.ssccopsserver.domain.member.dto.MemberProfileResponse;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;
import org.sscc.ssccopsserver.global.security.AuthenticatedUser;

/*
 * 계정 연결의 시도 횟수 제한 (#86 · VR-M24).
 *
 * ── @Transactional을 걸 수 없다 ────────────────────────────────
 * 잠금까지 가려면 실패 요청이 여러 건 이어져야 하는데, 테스트에 트랜잭션을 걸면 첫 실패가
 * 참여 트랜잭션을 rollback-only로 표시해 두 번째 호출부터 UnexpectedRollbackException을 만난다
 * (MemberSignupRollbackTest·RoleAuthoritySelfLockTest와 같은 이유).
 *
 * ── 그래서 DB를 따로 쓴다 ──────────────────────────────────────
 * 연결에 성공한 회원이 커밋되어 남으므로 공용 H2(testdb)를 쓰면 다른 테스트 클래스가 실행
 * 순서에 따라 깨진다. URL을 바꿔 이 클래스만의 DB를 띄운다 — 프로퍼티가 다르므로 컨텍스트도
 * 따로 잡히고, 그 덕에 시도 카운터(싱글턴 빈)도 이 클래스 전용이 된다.
 */
@SpringBootTest(
        properties =
                "spring.datasource.url="
                        + "jdbc:h2:mem:link-attempt;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
@ActiveProfiles("test")
class MemberLinkAttemptLimitTest {

    private static final String STUDENT_NUMBER = "20190123";
    private static final String NAME = "김도현";
    private static final String PHONE = "010-1111-2222";

    @Autowired private MemberService memberService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;

    /*
     * 실패가 상한에 닿으면 **맞는 값을 넣어도** 잠긴다. 잠금 판정이 후보 조회보다 앞이라는
     * 뜻이며, 그래야 학번을 바꿔 가며 명부를 훑는 일 자체를 막는다.
     *
     * 잠기는 것은 그 계정 하나다 — 다른 계정은 같은 순간에도 정상적으로 연결된다. 제한 단위를
     * IP가 아니라 계정으로 둔 이유가 이것이다(공유망 뒤의 무고한 사람이 함께 잠기지 않는다).
     */
    @Test
    void repeatedFailuresLockTheAccountButNotOtherAccounts() {
        saveRosterMember();
        UUID guesser = UUID.randomUUID();

        for (int attempt = 0; attempt < MemberLinkAttemptLimiter.MAX_FAILURES; attempt++) {
            // 학번을 바꿔 가며 명부를 훑는 모양 그대로다
            MemberLinkRequest guess =
                    new MemberLinkRequest("2019%04d".formatted(attempt), NAME, PHONE);
            assertThatThrownBy(() -> memberService.link(user(guesser), guess))
                    .isInstanceOf(GeneralException.class)
                    .extracting(ex -> ((GeneralException) ex).getErrorCode())
                    .isEqualTo(MemberErrorCode.MEMBER_LINK_FAILED);
        }

        MemberLinkRequest correct = new MemberLinkRequest(STUDENT_NUMBER, NAME, PHONE);
        assertThatThrownBy(() -> memberService.link(user(guesser), correct))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(MemberErrorCode.TOO_MANY_LINK_ATTEMPTS);

        MemberProfileResponse linked = memberService.link(user(UUID.randomUUID()), correct);
        assertThat(linked.studentNumber()).isEqualTo(STUDENT_NUMBER);
    }

    private void saveRosterMember() {
        memberRepository.deleteAll();
        memberRepository.saveAndFlush(
                MemberEntity.create(
                        STUDENT_NUMBER,
                        25,
                        NAME,
                        "컴퓨터학부",
                        4,
                        PHONE,
                        null,
                        memberGradeRepository.findById(MemberGradeCode.FULL.code()).orElseThrow(),
                        memberStatusRepository
                                .findById(MemberStatusCode.ENROLLED.code())
                                .orElseThrow(),
                        LocalDate.of(2019, 3, 1)));
    }

    private static AuthenticatedUser user(UUID authUserId) {
        return new AuthenticatedUser(authUserId, "someone@sscc.org", "누군가", "google", null);
    }
}
