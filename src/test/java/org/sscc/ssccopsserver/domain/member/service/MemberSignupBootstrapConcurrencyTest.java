package org.sscc.ssccopsserver.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.sscc.ssccopsserver.domain.member.code.MemberStatusCode;
import org.sscc.ssccopsserver.domain.member.dto.MemberProfileResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberSignupRequest;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.global.security.AuthenticatedUser;

/*
 * 동시 가입 두 건이 최고관리자를 둘 만들지 않는지 (#71 · VR-M14).
 *
 * ── @Transactional을 걸 수 없다 ────────────────────────────────
 * 두 스레드가 서로의 INSERT를 보려면 진짜 커밋이 일어나야 한다. 테스트에 트랜잭션을 걸면
 * 두 스레드가 각자 다른 트랜잭션에서 돌아 아무것도 보지 못하고, 검증하려는 경합 자체가
 * 재현되지 않는다 (MemberSignupRollbackTest·RoleAuthoritySelfLockTest와 같은 이유).
 *
 * ── 그래서 DB를 따로 쓴다 ──────────────────────────────────────
 * 커밋한 회원이 남으므로 공용 H2(testdb)를 쓰면 "회원이 한 명도 없는 상태"를 전제하는 다른
 * 테스트 클래스가 실행 순서에 따라 깨진다. URL을 바꿔 이 클래스만의 DB를 띄운다 — 프로퍼티가
 * 다르므로 컨텍스트도 따로 잡히고, data.sql이 그 DB에 기준 데이터를 다시 시드한다.
 * 뒷정리에 기대는 대신 애초에 남 볼 일이 없게 만드는 쪽이 순서 의존을 없앤다.
 */
@SpringBootTest(
        properties =
                "spring.datasource.url="
                    + "jdbc:h2:mem:bootstrap-concurrency;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
@ActiveProfiles("test")
class MemberSignupBootstrapConcurrencyTest {

    private static final int CONCURRENT_SIGNUPS = 2;

    @Autowired private MemberService memberService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;

    @Test
    void simultaneousSignupsIntoAnEmptySystemProduceExactlyOneSuperAdmin() throws Exception {
        assertThat(memberRepository.count()).isZero();

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_SIGNUPS);
        CountDownLatch startTogether = new CountDownLatch(1);
        List<Future<MemberProfileResponse>> results = new ArrayList<>();

        try {
            for (int index = 0; index < CONCURRENT_SIGNUPS; index++) {
                int seq = index;
                results.add(
                        pool.submit(
                                () -> {
                                    startTogether.await();
                                    return memberService.signUp(user(seq), request(seq));
                                }));
            }
            startTogether.countDown();

            List<MemberProfileResponse> profiles = new ArrayList<>();
            for (Future<MemberProfileResponse> result : results) {
                // 부트스트랩은 가입을 막는 규칙이 아니다 — 두 사람 다 회원이 되어야 한다
                profiles.add(result.get(30, TimeUnit.SECONDS));
            }

            assertThat(memberRepository.count()).isEqualTo(CONCURRENT_SIGNUPS);

            // 배정이 하나뿐이라는 것이 곧 최고관리자가 한 명이라는 뜻이다
            assertThat(memberRoleAssignmentRepository.findAll()).hasSize(1);
            assertThat(profiles).filteredOn(profile -> !profile.roles().isEmpty()).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private static AuthenticatedUser user(int seq) {
        return new AuthenticatedUser(
                UUID.randomUUID(),
                "founder%d@sscc.org".formatted(seq),
                "가입자" + seq,
                "google",
                null);
    }

    private static MemberSignupRequest request(int seq) {
        return new MemberSignupRequest(
                "가입자" + seq,
                "010-0000-000" + seq,
                MemberStatusCode.ENROLLED,
                "2020000" + seq,
                "컴퓨터학부",
                3,
                null);
    }
}
