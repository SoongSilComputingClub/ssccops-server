package org.sscc.ssccopsserver.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
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
 * 같은 명부 회원에 두 계정이 동시에 연결을 시도하면 한 쪽만 성공한다 (#86).
 *
 * 두 요청이 나란히 후보 조회를 통과하는 것은 정상이다 — 둘 다 auth_user_id가 비어 있는 것을
 * 본다. 갈라지는 자리는 그 뒤의 잠금·재확인(MemberServiceImpl.lockForLink)이며, 진 쪽은
 * 409 MEMBER_ALREADY_LINKED다. 막지 못하면 늦게 커밋한 쪽이 조용히 이기고 먼저 연결한 사람은
 * 성공 응답을 받고도 계정을 잃는다.
 *
 * @Transactional을 걸 수 없는 이유는 MemberSignupBootstrapConcurrencyTest의 주석과 같다 —
 * 두 스레드가 서로의 커밋을 봐야 경합 자체가 재현된다. 다만 저쪽과 달리 전용 DB를 띄우지
 * 않고 만든 회원을 @AfterEach로 지운다. 프로퍼티를 달리하면 스프링 테스트 컨텍스트가 한 벌 더
 * 잡히는데, 이 저장소는 이미 캐시 한계 언저리라 그것만으로 무관한 테스트가 터진다
 * (MemberLinkAttemptLimitTest 주석 · application-test.yaml의 ddl-auto·otel 주석).
 */
@SpringBootTest
@ActiveProfiles("test")
class MemberLinkConcurrencyTest {

    private static final int CONCURRENT_LINKS = 2;
    private static final String STUDENT_NUMBER = "20190123";
    private static final String NAME = "김도현";
    private static final String PHONE = "010-1111-2222";

    @Autowired private MemberService memberService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;

    private Long rosterMemberId;

    /*
     * 트랜잭션이 없어 커밋된 회원이 그대로 남는다. 공용 DB를 쓰므로 만든 것은 여기서 지운다 —
     * 남기면 'mbr이 비어 있다'를 전제하는 테스트가 실행 순서에 따라 깨진다.
     */
    @AfterEach
    void removeRosterMember() {
        if (rosterMemberId != null) {
            memberRepository.deleteById(rosterMemberId);
        }
    }

    @Test
    void simultaneousLinksToTheSameRosterMemberLeaveExactlyOneWinner() throws Exception {
        saveRosterMember();
        List<UUID> accounts = List.of(UUID.randomUUID(), UUID.randomUUID());

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_LINKS);
        CountDownLatch startTogether = new CountDownLatch(1);
        List<Future<MemberProfileResponse>> results = new ArrayList<>();

        try {
            for (UUID account : accounts) {
                results.add(
                        pool.submit(
                                () -> {
                                    startTogether.await();
                                    return memberService.link(user(account), request());
                                }));
            }
            startTogether.countDown();

            int succeeded = 0;
            for (Future<MemberProfileResponse> result : results) {
                if (linkSucceeded(result)) {
                    succeeded++;
                }
            }
            assertThat(succeeded).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        // 회원은 여전히 그 한 행이고, 이긴 쪽 하나만 붙어 있다
        assertThat(memberRepository.findLinkCandidatesByStudentNumber(STUDENT_NUMBER)).isEmpty();
        UUID linkedAccount =
                memberRepository.findById(rosterMemberId).orElseThrow().getAuthUserId();
        assertThat(linkedAccount).isIn(accounts);
    }

    /*
     * 진 쪽은 반드시 409 MEMBER_ALREADY_LINKED다. 다른 예외(제약 위반이 그대로 새어 나간
     * 500 등)면 화면이 "다시 로그인하라"와 "운영진에게 문의하라"를 가릴 수 없으므로 실패로 둔다.
     */
    private static boolean linkSucceeded(Future<MemberProfileResponse> result) throws Exception {
        try {
            result.get(30, TimeUnit.SECONDS);
            return true;
        } catch (ExecutionException ex) {
            assertThat(ex.getCause()).isInstanceOf(GeneralException.class);
            assertThat(((GeneralException) ex.getCause()).getErrorCode())
                    .isEqualTo(MemberErrorCode.MEMBER_ALREADY_LINKED);
            return false;
        }
    }

    private void saveRosterMember() {
        rosterMemberId =
                memberRepository
                        .saveAndFlush(
                                MemberEntity.create(
                                        STUDENT_NUMBER,
                                        25,
                                        NAME,
                                        "컴퓨터학부",
                                        4,
                                        PHONE,
                                        null,
                                        memberGradeRepository
                                                .findById(MemberGradeCode.FULL.code())
                                                .orElseThrow(),
                                        memberStatusRepository
                                                .findById(MemberStatusCode.ENROLLED.code())
                                                .orElseThrow(),
                                        LocalDate.of(2019, 3, 1)))
                        .getId();
    }

    private static MemberLinkRequest request() {
        return new MemberLinkRequest(STUDENT_NUMBER, NAME, PHONE);
    }

    private static AuthenticatedUser user(UUID authUserId) {
        return new AuthenticatedUser(authUserId, "someone@sscc.org", NAME, "google", null);
    }
}
