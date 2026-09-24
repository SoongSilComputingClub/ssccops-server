package org.sscc.ssccopsserver.domain.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/*
 * 스케줄러가 «다른 빈»을 부르는가 (#570 · ssccops#502).
 *
 * ── 이 테스트가 생긴 이유 ────────────────────────────────────────────────
 *
 * 처음에는 스케줄 메서드와 `@Transactional purge()`가 **같은 클래스**에 있었다. `this.purge()`는
 * Spring 프록시를 지나지 않으므로 그 애노테이션은 아무 일도 하지 않았고, 삭제가 `@Modifying`
 * 벌크 질의라 실제로 도는 순간 `TransactionRequiredException`으로 죽는다 — 그 예외는 스케줄러의
 * `catch`가 받아 **주 1회 로그 한 줄**로만 남았을 것이다.
 *
 * **기존 테스트는 이것을 볼 수 없었다.** `FormDraftRetentionQueryTest`는 `@DataJpaTest`라 테스트
 * 자체가 트랜잭션 안에서 돌아 질의가 정상으로 보이고, 그 파일의 주석은 «스케줄러는 cron 껍데기»
 * 라고 적고 있었다 — 껍데기가 아니라 배선이었고 결함은 거기 있었다. Sonar의 `java:S2229`·
 * `java:S6809`가 잡았다.
 *
 * 그래서 여기서 보는 것은 «지워지는가»가 아니라 **모양**이다: 스케줄러가 주입받은 빈을 부르는가,
 * 그 빈의 메서드에 `@Transactional`이 붙어 있는가. 둘이 같은 클래스로 되돌아오면 깨진다.
 */
class FormDraftRetentionSchedulerTest {

    private final FormDraftRetentionService service = mock(FormDraftRetentionService.class);
    private final FormDraftRetentionScheduler scheduler = new FormDraftRetentionScheduler(service);

    @Test
    @DisplayName("스케줄러는 주입받은 빈에 삭제를 맡긴다 — 자기 메서드를 부르면 트랜잭션이 걸리지 않는다")
    void delegatesToTheInjectedBean() {
        when(service.purge()).thenReturn(3);

        scheduler.purgeClosedFormDrafts();

        verify(service).purge();
    }

    @Test
    @DisplayName("삭제가 실패해도 스케줄러는 던지지 않는다 — 다음 주에 다시 돈다")
    void swallowsFailureSoTheScheduleSurvives() {
        when(service.purge()).thenThrow(new IllegalStateException("커넥션을 얻지 못했다"));

        scheduler.purgeClosedFormDrafts();

        verify(service).purge();
    }

    @Test
    @DisplayName("삭제 메서드에 @Transactional이 붙어 있다 — 벌크 질의라 없으면 실행 시점에 죽는다")
    void purgeIsTransactional() throws NoSuchMethodException {
        Method purge = FormDraftRetentionService.class.getMethod("purge");

        assertThat(purge.getAnnotation(Transactional.class))
                .describedAs("FormDraftRetentionService.purge 에 @Transactional 이 있어야 한다")
                .isNotNull();
    }

    @Test
    @DisplayName("스케줄 메서드는 삭제를 스스로 하지 않는다 — 배선이 되돌아오면 여기서 걸린다")
    void schedulerDoesNotDoTheDeletionItself() {
        assertThat(FormDraftRetentionScheduler.class.getDeclaredMethods())
                .describedAs("스케줄러에 @Transactional 메서드가 있으면 self-invocation 으로 되돌아간 것이다")
                .noneMatch(method -> method.getAnnotation(Transactional.class) != null);

        // 저장소를 직접 들고 있으면 언젠가 여기서 지우게 된다
        assertThat(FormDraftRetentionScheduler.class.getDeclaredFields())
                .describedAs("스케줄러는 서비스 하나만 안다")
                .allMatch(field -> !field.getType().getSimpleName().endsWith("Repository"));
    }
}
