package org.sscc.ssccopsserver.domain.academicprogram.service;

import java.util.ArrayList;
import java.util.List;

import org.sscc.ssccopsserver.domain.academicprogram.dto.CurriculumItemDraft;
import org.sscc.ssccopsserver.domain.academicprogram.dto.ProposalDraft;

/*
 * 승인된 기획안을 행사 본문(mtxt_cn) 마크다운으로 옮기는 자리 (#222 · ssccops#158).
 *
 * ── 왜 목표 하나만 넣던 것을 바꿨나 ────────────────────────────
 *
 * 원래 이관은 `goal_cn`만 본문에 복사했고, 그 근거는 "리더가 모집 공고를 쓸 때 백지가 아니라
 * 자기가 낸 기획안에서 시작하게 한다"였다 — 즉 본문은 **사람이 마저 쓰는 초안**이라는 전제였다.
 * 실제 운영에서는 리더가 그것을 고치지 않은 채 모집을 시작해, 지원자가 보는 공고에 활동 소개
 * 한 문단만 뜨고 커리큘럼·준비물·일정이 통째로 빠졌다(ssccops#158). 제출자가 이미 적어 낸
 * 정보이므로 옮기지 않을 이유가 없다.
 *
 * 그래서 전제가 바뀌었다 — 본문은 **기획안을 정해진 규칙으로 치환한 결과**이고, 리더가 고치는
 * 것은 그 위에서 하는 일이다. 이관은 여전히 복사라 이후 양쪽은 각자 편집된다(역방향 동기화 없음).
 *
 * ── 이관 서비스가 아니라 여기에 두는 이유 ──────────────────────
 *
 * 문서 포맷을 아는 것과 엔티티를 만드는 것은 다른 일이다. 이관 서비스 안에 인라인하면 그쪽이
 * 마크다운 문법을 알게 되고, 이 도메인이 이미 지켜 온 분리(파싱은 ProposalResponseParser ·
 * 커리큘럼 줄 해석은 ProposalCurriculumParser)와 어긋난다. 주입할 것이 없는 순수 변환이라
 * 스프링 빈이 아니라 정적 유틸이다(AcademicProfilePolicy·GenerationPolicy와 같은 태도).
 *
 * ── 마크다운 규칙 (공개 앱 렌더러에 맞춘 것) ────────────────────
 *
 * 공개 웹(apps/www)은 `react-markdown` + `remark-gfm`이고 **원시 HTML을 해석하지 않는다**
 * (wave2 D12 — `rehype-raw`를 일부러 쓰지 않는다). 그래서 여기서 만드는 것은 태그가 없는
 * 순수 마크다운이며, 표는 GFM 파이프 표를 쓴다.
 *
 * - **최상위는 `##`다.** 행사 제목이 이미 화면의 제목이라 본문에 `#`을 또 두면 제목이 둘이 된다.
 * - **빈 섹션은 제목까지 통째로 생략한다.** 제목만 있고 내용이 없는 섹션이 남으면 "리더가 아직
 *   안 쓴 것"과 "제출자가 원래 적지 않은 것"이 구별되지 않는다. `prepContent`·`scheduleText`는
 *   선택 문항이라 실제로 비어서 온다(파서가 공백만 있는 답도 null로 굳힌다).
 * - **없는 계획일을 지어내지 않는다.** `planYmd`는 NULL 허용이고("1회차 | 주제 |"처럼 날짜를
 *   생략한 줄) 그 자리는 `-`로 둔다.
 * - **기간·장소·정원은 본문에 넣지 않는다.** 셋 다 event 컬럼으로 저장되고 공개 상세 화면이 그
 *   컬럼을 따로 그리므로, 본문에 또 넣으면 같은 정보가 한 화면에 두 번 보인다. 본문이 맡는 것은
 *   컬럼으로 표현되지 않는 서술형 정보다.
 *
 * **커리큘럼 주제를 이스케이프하지 않는 것은 `|`가 들어올 수 없기 때문이다** —
 * `ProposalCurriculumParser`가 줄을 `|`로 쪼개 필드 수가 2~3이 아니면 거절하고, 줄 자체도
 * 개행으로 나눈다. 그 파서가 구분자를 바꾸게 되면 이 가정도 함께 봐야 한다.
 */
public final class ProposalEventBodyWriter {

    private static final String CURRICULUM_TABLE_HEADER = "| 회차 | 내용 | 계획일 |\n| --- | --- | --- |";

    /** 계획일을 적지 않은 회차. 빈 칸으로 두면 표가 무너져 보인다 */
    private static final String NO_PLAN_DATE = "-";

    private ProposalEventBodyWriter() {}

    /*
     * 기획안을 행사 본문 마크다운으로 옮긴다.
     *
     * 활동 소개(goal_cn)는 폼 계약이 잠근 필수 문항이라 언제나 채워져 있고, 그래서 결과가 빈
     * 문자열이 되지 않는다 — mtxt_cn이 NOT NULL이라는 사실이 여기에 기대고 있다.
     */
    public static String write(ProposalDraft draft) {
        List<String> sections = new ArrayList<>();
        addSection(sections, "활동 소개", draft.goalContent());
        addSection(sections, "준비물", draft.prepContent());
        addSection(sections, "일정", draft.scheduleText());
        addSection(sections, "커리큘럼", curriculumTable(draft.curriculumItems()));
        return String.join("\n\n", sections);
    }

    /** 내용이 없으면 제목도 만들지 않는다 */
    private static void addSection(List<String> sections, String heading, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        sections.add("## " + heading + "\n\n" + content.strip());
    }

    /*
     * 회차 계획 표. 제출자가 적은 순서를 그대로 두고 회차 번호로 다시 정렬하지 않는다 —
     * 번호가 곧 순서라는 보장은 파서가 중복만 막을 뿐 연속성까지 요구하지는 않기 때문이다.
     */
    private static String curriculumTable(List<CurriculumItemDraft> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        StringBuilder table = new StringBuilder(CURRICULUM_TABLE_HEADER);
        for (CurriculumItemDraft item : items) {
            table.append("\n| ")
                    .append(item.seqno())
                    .append(" | ")
                    .append(item.ttl())
                    .append(" | ")
                    .append(item.planYmd() == null ? NO_PLAN_DATE : item.planYmd())
                    .append(" |");
        }
        return table.toString();
    }
}
