package org.sscc.ssccopsserver.domain.academicprogram.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sscc.ssccopsserver.domain.academicprogram.dto.CurriculumItemDraft;
import org.sscc.ssccopsserver.domain.academicprogram.dto.ProposalDraft;

/*
 * 기획안 → 행사 본문 마크다운 치환 (#222 · ssccops#158).
 *
 * 결과 문자열을 통째로 비교하는 것은 이 값이 **지원자가 읽는 공고 그 자체**이기 때문이다.
 * 섹션이 있는지만 확인하면 순서가 뒤바뀌거나 빈 줄이 사라져 표가 깨져도 초록으로 남는다.
 */
class ProposalEventBodyWriterTest {

    @Test
    void writesEverySectionInOrder() {
        String body = ProposalEventBodyWriter.write(draft("노트북", "매주 화요일 19:00", CURRICULUM));

        assertThat(body)
                .isEqualTo(
                        """
                        ## 활동 소개

                        알고리즘 문제 풀이 근육을 만든다

                        ## 준비물

                        노트북

                        ## 일정

                        매주 화요일 19:00

                        ## 커리큘럼

                        | 회차 | 내용 | 계획일 |
                        | --- | --- | --- |
                        | 1 | 오리엔테이션 | 2026-03-05 |
                        | 2 | React, 그리고 상태관리 | - |"""
                                .stripTrailing());
    }

    /*
     * 선택 문항을 비워 낸 기획안. 제목만 있는 빈 섹션이 남으면 "리더가 아직 안 쓴 것"과
     * "제출자가 원래 적지 않은 것"이 구별되지 않는다.
     */
    @Test
    void omitsSectionsWhoseAnswerWasLeftBlank() {
        String body = ProposalEventBodyWriter.write(draft(null, null, CURRICULUM));

        assertThat(body).doesNotContain("## 준비물").doesNotContain("## 일정");
        assertThat(body).contains("## 활동 소개").contains("## 커리큘럼");
    }

    /* 파서가 답을 null로 굳히지만, 공백만 남은 값이 들어와도 같은 판단이어야 한다 */
    @Test
    void treatsBlankAnswerAsAbsent() {
        String body = ProposalEventBodyWriter.write(draft("   ", "\n\n", CURRICULUM));

        assertThat(body).doesNotContain("## 준비물").doesNotContain("## 일정");
    }

    /* 계획일을 적지 않은 회차. 없는 날짜를 지어내지 않고 '-'로 둔다 */
    @Test
    void leavesMissingPlanDateAsDash() {
        String body =
                ProposalEventBodyWriter.write(
                        draft(null, null, List.of(new CurriculumItemDraft(1, "오리엔테이션", null))));

        assertThat(body).contains("| 1 | 오리엔테이션 | - |");
    }

    /* 커리큘럼이 없으면 표가 아니라 섹션 자체가 없다 — 머리글만 남은 표는 깨진 것으로 보인다 */
    @Test
    void omitsCurriculumSectionWhenThereIsNoItem() {
        assertThat(ProposalEventBodyWriter.write(draft(null, null, List.of())))
                .doesNotContain("## 커리큘럼")
                .doesNotContain("| 회차 |");
        assertThat(
                        ProposalEventBodyWriter.write(
                                draft(null, null, (List<CurriculumItemDraft>) null)))
                .doesNotContain("## 커리큘럼");
    }

    /*
     * 본문 최상위는 `##`다. 행사 제목이 이미 화면의 제목이라 `#`을 또 두면 제목이 둘이 된다.
     * 공개 앱 렌더러는 원시 HTML을 해석하지 않으므로(D12) 태그가 섞이면 그대로 글자로 보인다.
     */
    @Test
    void producesPlainMarkdownWithoutTopLevelHeadingOrHtml() {
        String body = ProposalEventBodyWriter.write(draft("노트북", "매주 화요일", CURRICULUM));

        assertThat(body).doesNotContain("<").startsWith("## ");
        assertThat(body.lines()).noneMatch(line -> line.startsWith("# "));
    }

    /*
     * 기간·장소·정원은 event 컬럼으로 저장되고 공개 상세가 그 컬럼을 따로 그린다.
     * 본문에 또 넣으면 한 화면에 두 번 보인다.
     */
    @Test
    void doesNotRepeatWhatTheEventColumnsAlreadyHold() {
        String body = ProposalEventBodyWriter.write(draft("노트북", "매주 화요일", CURRICULUM));

        assertThat(body)
                .doesNotContain("전산관 401호")
                .doesNotContain("2026-03-02")
                .doesNotContain("12");
    }

    private static final List<CurriculumItemDraft> CURRICULUM =
            List.of(
                    new CurriculumItemDraft(1, "오리엔테이션", LocalDate.of(2026, 3, 5)),
                    new CurriculumItemDraft(2, "React, 그리고 상태관리", null));

    private static ProposalDraft draft(
            String prepContent, String scheduleText, List<CurriculumItemDraft> curriculum) {
        return new ProposalDraft(
                null,
                "알고리즘 스터디",
                "알고리즘 문제 풀이 근육을 만든다",
                prepContent,
                LocalDate.of(2026, 3, 2),
                LocalDate.of(2026, 6, 30),
                scheduleText,
                4,
                12,
                "전산관 401호",
                curriculum);
    }
}
