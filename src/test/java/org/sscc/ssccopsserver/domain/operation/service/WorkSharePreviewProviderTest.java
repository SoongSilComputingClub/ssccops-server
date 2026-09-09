package org.sscc.ssccopsserver.domain.operation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.operation.entity.OperationEntity;
import org.sscc.ssccopsserver.domain.operation.entity.OperationPriority;
import org.sscc.ssccopsserver.domain.operation.entity.WorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.WorkType;
import org.sscc.ssccopsserver.domain.operation.repository.WorkRepository;
import org.sscc.ssccopsserver.domain.share.code.ShareTargetType;
import org.sscc.ssccopsserver.domain.share.service.SharePreview;

/*
 * 업무 미리보기의 요약 조립 (ssccops#306).
 *
 * **여기서 보는 것은 문장이 깨지지 않는가 하나다.** 업무에는 본문이 없어 요약을 서버가 만드는데
 * (`WorkSharePreviewProvider` 주석), 재료인 `bgng_dt`·`end_dt`가 둘 다 nullable이라 조합이 넷이다.
 * 통합 테스트로는 그 넷을 다 태우려고 업무를 넷 만들어야 하므로 여기서 본다 —
 * `ShareLinkControllerTest`는 발급·폐기·익명 열람이 이어지는지를 본다.
 */
@ExtendWith(MockitoExtension.class)
class WorkSharePreviewProviderTest {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    @Mock private WorkRepository workRepository;

    @InjectMocks private WorkSharePreviewProvider provider;

    @Test
    void targetTypeIsWork() {
        assertThat(provider.targetType()).isEqualTo(ShareTargetType.WORK);
    }

    /*
     * 제목은 업무 자기 것이 아니라 부모 운영 건의 것이다 — work에는 제목 컬럼이 없다.
     */
    @Test
    void titleComesFromTheParentOperation() {
        givenWork(WorkType.EVENT, date(2026, 9, 15), date(2026, 9, 20));

        assertThat(preview().title()).isEqualTo("2026 동아리 박람회");
    }

    /* ── 요약 조립 ─────────────────────────────────────────── */

    // 같은 해면 끝 날짜의 연도를 뗀다 — ssccops#251이 든 예 그대로다
    @Test
    void summaryJoinsTypeAndPeriod() {
        givenWork(WorkType.EVENT, date(2026, 9, 15), date(2026, 9, 20));

        assertThat(preview().summary()).isEqualTo("행사 · 2026-09-15 ~ 09-20");
    }

    // 해가 다르면 붙인다. 떼면 거꾸로 가는 기간처럼 읽힌다
    @Test
    void summaryKeepsTheYearWhenThePeriodCrossesIt() {
        givenWork(WorkType.ROUTINE, date(2026, 12, 28), date(2027, 1, 3));

        assertThat(preview().summary()).isEqualTo("정례운영 · 2026-12-28 ~ 2027-01-03");
    }

    /*
     * **기간이 아예 없으면 유형만 남는다.** 등록 화면에서 기간은 선택 입력이라 실제로 비어 있는
     * 업무가 있고, 그때 `... · ~`처럼 재료 없는 구분자가 남으면 카드가 잘린 것처럼 보인다.
     */
    @Test
    void summaryIsTypeOnlyWhenThereIsNoPeriod() {
        givenWork(WorkType.EVENT, null, null);

        assertThat(preview().summary()).isEqualTo("행사");
    }

    // 한쪽만 있을 때도 물결을 남기지 않는다 — `2026-09-15 ~`는 잘린 문자열로 읽힌다
    @Test
    void summaryReadsAsOpenEndedWhenOnlyTheStartIsSet() {
        givenWork(WorkType.REGULAR, date(2026, 9, 15), null);

        assertThat(preview().summary()).isEqualTo("상시 · 2026-09-15부터");
    }

    @Test
    void summaryReadsAsOpenEndedWhenOnlyTheEndIsSet() {
        givenWork(WorkType.REGULAR, null, date(2026, 9, 20));

        assertThat(preview().summary()).isEqualTo("상시 · 2026-09-20까지");
    }

    /*
     * 날짜를 자르는 기준은 서비스 타임존이다. UTC로 자르면 한국 시각 9월 16일 오전 8시가
     * 9월 15일로 적힌다 — 카드에 하루 어긋난 날짜가 굳는다.
     */
    @Test
    void datesAreCutInTheServiceZoneNotUtc() {
        Instant justAfterMidnightInSeoul = Instant.parse("2026-09-15T15:30:00Z");
        givenWork(WorkType.EVENT, justAfterMidnightInSeoul, null);

        assertThat(preview().summary()).isEqualTo("행사 · 2026-09-16부터");
    }

    /* ── 없는 것으로 답하는 자리 ───────────────────────────── */

    /*
     * 삭제된 운영 건은 질의가 걸러 낸다(`findByIdAndOperationDeletedAtIsNull`). 빈 Optional은
     * 폐기된 링크와 같은 404로 나간다 — 지운 업무의 제목이 링크로 계속 열리면 "지웠다"는
     * 화면의 표시가 사실이 아니게 된다.
     */
    @Test
    void missingOrDeletedWorkYieldsEmpty() {
        given(workRepository.findByIdAndOperationDeletedAtIsNull(1L)).willReturn(Optional.empty());

        assertThat(provider.preview(1L)).isEmpty();
    }

    /* ── 표본 ───────────────────────────────────────────────── */

    private void givenWork(WorkType workType, Instant beginAt, Instant endAt) {
        MemberEntity member = Mockito.mock(MemberEntity.class);
        OperationEntity operation =
                OperationEntity.createForWork(
                        "2026 동아리 박람회", member, member, beginAt, endAt, OperationPriority.NORMAL);
        WorkEntity work = WorkEntity.create(operation, workType, null);
        given(workRepository.findByIdAndOperationDeletedAtIsNull(1L)).willReturn(Optional.of(work));
    }

    private SharePreview preview() {
        return provider.preview(1L).orElseThrow();
    }

    private Instant date(int year, int month, int day) {
        return LocalDate.of(year, month, day)
                .atTime(LocalTime.NOON)
                .atZone(SERVICE_ZONE)
                .toInstant();
    }
}
