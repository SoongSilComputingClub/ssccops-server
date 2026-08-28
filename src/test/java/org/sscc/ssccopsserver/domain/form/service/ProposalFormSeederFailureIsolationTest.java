package org.sscc.ssccopsserver.domain.form.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.sscc.ssccopsserver.domain.form.repository.FormLabelRelationRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormLabelRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.member.event.MemberCreatedEvent;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;

/*
 * 시드가 실패해도 가입은 성공한다 (#184).
 *
 * ── 왜 이 자리를 따로 시험하는가 ───────────────────────────────
 * AFTER_COMMIT 리스너의 예외는 **삼켜지지 않는다.** commit()을 부른 쪽으로 올라가므로, 시드가
 * 터지면 회원은 이미 커밋됐는데 가입 API만 500으로 떨어진다 — 데이터와 응답이 어긋나는, 가장
 * 나쁜 모양이다. 가입은 기획안 폼과 무관하게 성공해야 하는 별개의 일이다.
 *
 * 그래서 onMemberCreated는 예외를 **트랜잭션 경계 바깥에서** 잡는다. @Transactional을 걸면
 * 커밋이 프록시를 빠져나간 뒤에 일어나 메서드 안쪽 try로는 잡히지 않으므로, 경계를 코드로 들고
 * 있는 TransactionTemplate을 쓴다. 그 구조가 무너지면 이 테스트가 예외를 그대로 받아 빨개진다.
 *
 * ── 통합 테스트가 아니라 목인 이유 ─────────────────────────────
 * 시드를 실제로 실패시키려면 폼 저장이 깨지는 DB 상태를 만들어야 하는데, 그런 상태는 이 저장소를
 * 통째로 이상하게 만들거나(제약 삭제) 실패 원인이 시드가 아닌 다른 데 있게 된다. 여기서 확인할
 * 것은 "실패가 밖으로 새지 않는가" 하나이므로 실패를 주입하는 쪽이 정확하다.
 */
class ProposalFormSeederFailureIsolationTest {

    @Test
    void swallowsSeedFailureSoThatTheSignupThatTriggeredItStillSucceeds() {
        FormRepository formRepository = mock(FormRepository.class);
        given(formRepository.findBySystemFormCode(any()))
                .willThrow(new IllegalStateException("시드 조회가 깨졌다"));

        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        SimpleTransactionStatus status = new SimpleTransactionStatus();
        given(transactionManager.getTransaction(any())).willReturn(status);

        ProposalFormSeeder seeder =
                new ProposalFormSeeder(
                        formRepository,
                        mock(FormLabelRepository.class),
                        mock(FormLabelRelationRepository.class),
                        mock(MemberRepository.class),
                        mock(QuestionCompositionValidator.class),
                        transactionManager,
                        true);

        assertThatCode(() -> seeder.onMemberCreated(new MemberCreatedEvent(1L)))
                .as("리스너가 던지면 이미 커밋된 가입이 500으로 떨어진다")
                .doesNotThrowAnyException();

        // 실패한 시드가 커밋되지 않고 롤백되었는지까지 본다 — 절반만 세워진 폼이 남으면 안 된다
        verify(transactionManager).rollback(status);
    }
}
