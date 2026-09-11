package org.sscc.ssccopsserver.domain.academicprogram.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramTypeEntity;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramRepository;
import org.sscc.ssccopsserver.domain.event.entity.EventClassificationEntity;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.share.code.ShareTargetType;
import org.sscc.ssccopsserver.domain.share.service.SharePreview;

/*
 * 학술 프로그램 미리보기의 재료 (ssccops#311).
 *
 * **여기서 보는 것은 무엇을 재료로 삼았는가 하나다** — 제목이 자기 것이 아니라 event의 것이고
 * (acdm_actv에 제목 컬럼이 없다), 요약은 조립하지 않은 `goal_cn`이며 익명에게 통째로 흘리지
 * 않는다. 발급·폐기·익명 열람이 이어지는지는 `AcademicProgramShareControllerTest`가 본다.
 */
@ExtendWith(MockitoExtension.class)
class AcademicProgramSharePreviewProviderTest {

    @Mock private AcademicProgramRepository academicProgramRepository;

    @InjectMocks private AcademicProgramSharePreviewProvider provider;

    @Test
    void targetTypeIsAcademicProgram() {
        assertThat(provider.targetType()).isEqualTo(ShareTargetType.ACADEMIC_PROGRAM);
    }

    /*
     * 제목은 1:1로 확장하는 event의 것이다 — acdm_actv에는 제목 컬럼이 없다(업무가 부모 운영
     * 건의 제목을 쓰는 것과 같은 자리다).
     */
    @Test
    void titleComesFromTheEvent() {
        givenProgram("알고리즘 스터디", "매주 문제를 풀며 그리디와 DP를 익힌다");

        assertThat(preview().title()).isEqualTo("알고리즘 스터디");
    }

    /*
     * 요약은 활동 목표 그대로다 — 업무와 달리 본문이 있으므로 서버가 문장을 조립하지 않는다.
     */
    @Test
    void summaryIsTheGoalContentAsWritten() {
        givenProgram("알고리즘 스터디", "매주 문제를 풀며 그리디와 DP를 익힌다");

        assertThat(preview().summary()).isEqualTo("매주 문제를 풀며 그리디와 DP를 익힌다");
    }

    /*
     * **자르는 이유는 표시가 아니라 새는 양이다.** 카드에 두 줄 남짓만 보이는데 목표 전체를
     * 익명 경로로 흘려보낼 이유가 없다.
     */
    @Test
    void summaryIsCutAtFiveHundredCharacters() {
        givenProgram("알고리즘 스터디", "가".repeat(700));

        assertThat(preview().summary()).hasSize(500);
    }

    /*
     * goal_cn은 NOT NULL이지만 공백만 든 값은 없는 것으로 접는다 — **서버가 대체 문구를 만들지
     * 않는다.** 채워 버리면 "목표가 비었다"와 "서버가 그 문구를 줬다"를 웹이 구별할 수 없다.
     */
    @Test
    void blankGoalYieldsNoSummaryInsteadOfAFabricatedOne() {
        givenProgram("알고리즘 스터디", "   ");

        assertThat(preview().summary()).isNull();
        assertThat(preview().title()).isEqualTo("알고리즘 스터디");
    }

    /*
     * 없는 활동은 빈 Optional이고 폐기된 링크와 같은 404로 나간다. **감출 상태는 그 밖에
     * 없다** — acdm_actv에는 소프트 삭제가 없고 상태 셋은 전부 실재하는 활동이라, 미리보기가
     * 상세보다 더 감출 것이 없다.
     */
    @Test
    void missingProgramYieldsEmpty() {
        given(academicProgramRepository.findById(1L)).willReturn(Optional.empty());

        assertThat(provider.preview(1L)).isEmpty();
    }

    /* ── 표본 ───────────────────────────────────────────────── */

    private void givenProgram(String title, String goalContent) {
        MemberEntity member = mock(MemberEntity.class);
        EventEntity event =
                EventEntity.create(
                        mock(EventClassificationEntity.class),
                        member,
                        title,
                        "본문",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        AcademicProgramEntity program =
                AcademicProgramEntity.create(
                        event,
                        mock(FormResponseHistoryEntity.class),
                        mock(AcademicProgramTypeEntity.class),
                        goalContent,
                        null,
                        null,
                        null,
                        null,
                        member);
        given(academicProgramRepository.findById(1L)).willReturn(Optional.of(program));
    }

    private SharePreview preview() {
        return provider.preview(1L).orElseThrow();
    }
}
