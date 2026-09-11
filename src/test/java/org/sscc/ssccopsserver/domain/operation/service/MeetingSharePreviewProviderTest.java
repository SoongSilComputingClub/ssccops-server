package org.sscc.ssccopsserver.domain.operation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.operation.entity.AttendeeScope;
import org.sscc.ssccopsserver.domain.operation.entity.MeetingCategory;
import org.sscc.ssccopsserver.domain.operation.entity.MeetingEntity;
import org.sscc.ssccopsserver.domain.operation.entity.OperationEntity;
import org.sscc.ssccopsserver.domain.operation.entity.OperationPriority;
import org.sscc.ssccopsserver.domain.operation.repository.MeetingRepository;
import org.sscc.ssccopsserver.domain.share.code.ShareTargetType;
import org.sscc.ssccopsserver.domain.share.service.SharePreview;

/*
 * 회의 미리보기의 요약 조립 (ssccops#310).
 *
 * **여기서 보는 것은 문장이 깨지지 않는가 하나다.** 회의에도 본문이 없어 요약을 서버가 만드는데
 * (`MeetingSharePreviewProvider` 주석), 재료인 `mtg_se_cd`·`bgng_dt`가 둘 다 NULL을 허용해
 * 조합이 넷이다. 통합 테스트로 그 넷을 다 태우려면 회의를 넷 만들어야 하므로 여기서 본다 —
 * `ShareLinkControllerTest`는 발급·폐기·익명 열람이 이어지는지를 본다
 * (`WorkSharePreviewProviderTest`와 같은 분담).
 */
@ExtendWith(MockitoExtension.class)
class MeetingSharePreviewProviderTest {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    @Mock private MeetingRepository meetingRepository;

    @InjectMocks private MeetingSharePreviewProvider provider;

    @Test
    void targetTypeIsMeeting() {
        assertThat(provider.targetType()).isEqualTo(ShareTargetType.MEETING);
    }

    /*
     * 제목은 회의 자기 것이 아니라 부모 운영 건의 것이다 — mtg에는 제목 컬럼이 없다.
     */
    @Test
    void titleComesFromTheParentOperation() {
        givenMeeting(MeetingCategory.REGULAR, at(2026, 9, 15, 19, 0));

        assertThat(preview().title()).isEqualTo("9월 정례회의");
    }

    /* ── 요약 조립 ─────────────────────────────────────────── */

    /*
     * 유형 + 일시. ssccops#252가 확정한 모양 그대로이며, 분까지 적는 것은 **몇 시에 모이는가가
     * 곧 그 회의**이기 때문이다 — 날짜까지만 적는 업무와 다른 자리다.
     */
    @Test
    void summaryJoinsCategoryAndStartAt() {
        givenMeeting(MeetingCategory.REGULAR, at(2026, 9, 15, 19, 0));

        assertThat(preview().summary()).isEqualTo("정례 · 2026-09-15 19:00");
    }

    /*
     * 표시명은 데이터사전의 `mtg_se_cd`를 따른다 — 웹 `codes.ts`의 `MtgSeCd`와 같은 낱말이라야
     * 카드와 화면이 같은 회의를 같은 이름으로 부른다.
     */
    @Test
    void topicMeetingUsesTheDictionaryDisplayName() {
        givenMeeting(MeetingCategory.TOPIC, at(2026, 9, 15, 19, 0));

        assertThat(preview().summary()).isEqualTo("주제 · 2026-09-15 19:00");
    }

    /*
     * **일시가 없으면 유형만 남는다.** `bgng_dt`는 컬럼이 NULL을 허용하고, 그때 `정례 · `처럼
     * 재료 없는 구분자가 남으면 카드가 잘린 것처럼 보인다.
     */
    @Test
    void summaryIsCategoryOnlyWhenThereIsNoStartAt() {
        givenMeeting(MeetingCategory.REGULAR, null);

        assertThat(preview().summary()).isEqualTo("정례");
    }

    // 반대쪽도 같다 — 유형이 비면 일시만 남고 구분자는 남지 않는다
    @Test
    void summaryIsStartAtOnlyWhenThereIsNoCategory() {
        givenMeeting(null, at(2026, 9, 15, 19, 0));

        assertThat(preview().summary()).isEqualTo("2026-09-15 19:00");
    }

    /*
     * 둘 다 없으면 **서버가 대체 문구를 지어내지 않는다.** 채워 버리면 "요약이 없다"와 "서버가
     * 그 문구를 줬다"를 웹이 구별할 수 없다(`SubWorkSharePreviewProvider`와 같은 규칙).
     */
    @Test
    void summaryIsNullWhenThereIsNeither() {
        givenMeeting(null, null);

        assertThat(preview().summary()).isNull();
    }

    /*
     * 시각을 적는 기준은 서비스 타임존이다. UTC로 적으면 한국 시각 저녁 7시가 오전 10시로
     * 나가고, 그 어긋난 시각이 카드에 굳는다.
     */
    @Test
    void startAtIsWrittenInTheServiceZoneNotUtc() {
        givenMeeting(MeetingCategory.REGULAR, Instant.parse("2026-09-15T10:00:00Z"));

        assertThat(preview().summary()).isEqualTo("정례 · 2026-09-15 19:00");
    }

    /* ── 없는 것으로 답하는 자리 ───────────────────────────── */

    /*
     * 삭제된 운영 건은 질의가 걸러 낸다(`findByIdAndOperationDeletedAtIsNull`, mtg에는 del_dt가
     * 없어 부모 oper를 본다). 빈 Optional은 폐기된 링크와 같은 404로 나간다 — 지운 회의의
     * 제목이 링크로 계속 열리면 "지웠다"는 화면의 표시가 사실이 아니게 된다.
     */
    @Test
    void missingOrDeletedMeetingYieldsEmpty() {
        given(meetingRepository.findByIdAndOperationDeletedAtIsNull(1L))
                .willReturn(Optional.empty());

        assertThat(provider.preview(1L)).isEmpty();
    }

    /* ── 표본 ───────────────────────────────────────────────── */

    private void givenMeeting(MeetingCategory meetingCategory, Instant beginAt) {
        MemberEntity member = mock(MemberEntity.class);
        OperationEntity operation =
                OperationEntity.createForMeeting(
                        "9월 정례회의", member, member, beginAt, null, OperationPriority.NORMAL);
        MeetingEntity meeting =
                MeetingEntity.create(
                        operation, meetingCategory, AttendeeScope.ALL, member, "정보과학관 5층");
        given(meetingRepository.findByIdAndOperationDeletedAtIsNull(1L))
                .willReturn(Optional.of(meeting));
    }

    private SharePreview preview() {
        return provider.preview(1L).orElseThrow();
    }

    private Instant at(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute).atZone(SERVICE_ZONE).toInstant();
    }
}
