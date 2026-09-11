package org.sscc.ssccopsserver.domain.academicprogram.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sscc.ssccopsserver.domain.academicprogram.entity.CurriculumItemEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.SessionEntity;
import org.sscc.ssccopsserver.domain.academicprogram.repository.SessionRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.share.code.ShareTargetType;
import org.sscc.ssccopsserver.domain.share.service.SharePreview;

/*
 * 회차 미리보기의 요약 조립 (ssccops#311).
 *
 * **여기서 보는 것은 문장이 깨지지 않는가와 무엇을 재료로 삼았는가다.** 회차에는 제목 컬럼이
 * 없어 계획의 주제를 쓰고, 요약은 실시일에 본문을 붙여 만드는데 그 본문이 공지·진행 내용 둘로
 * 갈린다. 발급·폐기·익명 열람이 이어지는지는 `AcademicProgramShareControllerTest`가 본다.
 */
@ExtendWith(MockitoExtension.class)
class SessionSharePreviewProviderTest {

    @Mock private SessionRepository sessionRepository;

    @InjectMocks private SessionSharePreviewProvider provider;

    @Test
    void targetTypeIsAcademicSession() {
        assertThat(provider.targetType()).isEqualTo(ShareTargetType.ACADEMIC_SESSION);
    }

    /*
     * 제목은 계획(crclm_artcl.ttl)의 것이다 — sesn에는 제목 컬럼이 없고, 회차가 무엇이었는지를
     * 말하는 낱말은 계획의 주제다.
     */
    @Test
    void titleComesFromTheCurriculumItem() {
        givenSession(LocalDate.of(2026, 9, 15), "재귀와 동적계획법", "3회차 진행 내용", "다음 주까지 3장 예제");

        assertThat(preview().title()).isEqualTo("재귀와 동적계획법");
    }

    /*
     * **실시일을 담는다.** 카드가 한 번 굳으므로 시간에 따라 변하는 값은 담지 않는데, 실시일은
     * 그런 값이 아니라 이미 일어난 일의 날짜다 — ssccops#251(업무 기간)·#252(회의 일시)와 같은
     * 판단이고, 기록 자체가 회차가 끝난 뒤에 쓰이므로 그중에서도 가장 확정적이다.
     */
    @Test
    void summaryLeadsWithTheRealDate() {
        givenSession(LocalDate.of(2026, 9, 15), "재귀와 동적계획법", "3회차 진행 내용", "다음 주까지 3장 예제");

        assertThat(preview().summary()).isEqualTo("2026-09-15 · 다음 주까지 3장 예제");
    }

    /*
     * 공지가 먼저다 — 이 링크를 뿌리는 이유가 회차를 부원에게 알리는 것이고, 공지는 바로 그
     * 목적으로 적힌 문장이다. 없으면 진행 내용으로 떨어지며, **둘 다 사람이 적은 실제 값이라
     * 대체 문구를 지어내는 것이 아니다.**
     */
    @Test
    void summaryFallsBackToTheProgressContentWhenThereIsNoNotice() {
        givenSession(LocalDate.of(2026, 9, 15), "재귀와 동적계획법", "3회차 진행 내용", null);

        assertThat(preview().summary()).isEqualTo("2026-09-15 · 3회차 진행 내용");
    }

    // 공백만 든 공지는 없는 것과 같다 — `2026-09-15 ·  `처럼 재료 없는 구분자가 남으면 안 된다
    @Test
    void blankNoticeIsTreatedAsAbsent() {
        givenSession(LocalDate.of(2026, 9, 15), "재귀와 동적계획법", "3회차 진행 내용", "   ");

        assertThat(preview().summary()).isEqualTo("2026-09-15 · 3회차 진행 내용");
    }

    /*
     * 본문이 아예 없으면 실시일만 남는다. `prgrs_cn`이 NOT NULL이라 드문 모양이지만, 재료가
     * 없을 때 구분자만 남으면 카드가 잘린 것처럼 보인다(업무가 물결을 남기지 않는 것과 같다).
     */
    @Test
    void summaryIsTheDateAloneWhenThereIsNoBody() {
        givenSession(LocalDate.of(2026, 9, 15), "재귀와 동적계획법", "  ", null);

        assertThat(preview().summary()).isEqualTo("2026-09-15");
    }

    /* 자르는 이유는 표시가 아니라 새는 양이다 — 날짜 접두사는 그 상한 밖이다 */
    @Test
    void summaryBodyIsCutAtFiveHundredCharacters() {
        givenSession(LocalDate.of(2026, 9, 15), "재귀와 동적계획법", "3회차 진행 내용", "가".repeat(700));

        assertThat(preview().summary()).hasSize("2026-09-15 · ".length() + 500);
    }

    /*
     * 없는 회차는 빈 Optional이고 폐기된 링크와 같은 404로 나간다. **상태로 감추지 않는다** —
     * 회차 상세 조회가 상태로 감추지 않으므로 미리보기도 감추지 않는다.
     */
    @Test
    void missingSessionYieldsEmpty() {
        given(sessionRepository.findWithCurriculumItemById(1L)).willReturn(Optional.empty());

        assertThat(provider.preview(1L)).isEmpty();
    }

    /* ── 표본 ───────────────────────────────────────────────── */

    private void givenSession(
            LocalDate realDate, String curriculumTitle, String content, String noticeContent) {
        CurriculumItemEntity curriculumItem =
                CurriculumItemEntity.create(null, 3, curriculumTitle, realDate);
        SessionEntity session =
                SessionEntity.submit(
                        curriculumItem, realDate, content, noticeContent, mock(MemberEntity.class));
        given(sessionRepository.findWithCurriculumItemById(1L)).willReturn(Optional.of(session));
    }

    private SharePreview preview() {
        return provider.preview(1L).orElseThrow();
    }
}
