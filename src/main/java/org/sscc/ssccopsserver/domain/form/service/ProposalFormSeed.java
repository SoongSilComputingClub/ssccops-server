package org.sscc.ssccopsserver.domain.form.service;

import java.util.List;
import java.util.Set;

import org.sscc.ssccopsserver.domain.form.code.QuestionItemType;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent.Page;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent.QuestionItem;

/*
 * 기획안 시스템 폼(sys_form_cd = 'PROPOSAL')의 초기 문항 구성과 qitemId 계약 (#173).
 *
 * ── 이 파일이 왜 계약인가 ─────────────────────────────────────
 * qitemId는 응답(form_rspns_hstry.rspns_cn)의 **key**다. 기획안이 한 건이라도 접수되는 순간
 * 여기 적힌 문자열은 바꿀 수 없다 — 바꾸면 이미 받은 답이 어느 문항의 답인지 알 수 없어지고,
 * 그 사실을 #140의 시스템 폼 계약(SYSTEM_FORM_CONTRACT_VIOLATION)과 응답이 있는 폼의
 * 문항 보호(QUESTION_ITEM_IN_USE)가 양쪽에서 못 박는다. 승인 시 학술 활동으로 이관하는
 * #150과 기획안 작성·검토 화면이 전부 이 값을 키로 읽는다.
 *
 * ── 왜 data.sql이 아니라 자바인가 ─────────────────────────────
 * #173의 체크리스트는 data.sql 시드를 적고 있었고 실제로 그렇게 쓰려 했지만, 문항 구성이
 * JSONB 컬럼(form.qitem_cpst_cn)이라 **H2와 PostgreSQL이 같은 SQL 리터럴을 다르게 읽는다.**
 * H2는 문자열을 JSON 컬럼에 넣을 때 `FORMAT JSON` 절이 없으면 그 문자열을 JSON '문자열 값'으로
 * 저장해(`"{\"pages\":...}"`) Hibernate가 다시 읽을 때 "Could not deserialize string to java
 * type"으로 터진다. 반대로 `FORMAT JSON`은 PostgreSQL이 값 표현식 자리에서 받지 않는 문법이라
 * 한 벌의 data.sql로는 로컬·테스트(H2)와 dev·prod(PostgreSQL)를 동시에 만족시킬 수 없다.
 * 후보였던 data-h2.sql / data-postgresql.sql 분리는 4KB짜리 같은 JSON을 두 파일에 복사하는
 * 것이라, 이 프로젝트가 계속 피해온 "같은 사실이 두 벌이 되면 갈린다"에 그대로 해당해 버렸다.
 *
 * 자바로 옮기면서 얻은 것이 하나 더 있다. qitemId가 SQL 문자열이 아니라 상수가 되어
 * SystemFormContract·이관(#150)·테스트가 **같은 상수**를 가리킨다 — SQL에 적었다면 계약 선언과
 * 시드 본문이 서로 다른 파일의 문자열 두 벌이 되어, 오타 하나가 배포 후에야 드러났을 것이다.
 * 실제로 폼을 세우는 것은 ProposalFormSeeder이며, 시스템 폼을 세우는 자리를
 * FormEntity.designateAsSystemForm 하나로 좁혀 둔 #140의 규칙도 그쪽에서 지켜진다
 * (그 메서드의 주석이 말하는 "시드나 이관 스크립트처럼 코드가 직접 세우는 경로"가 여기다).
 *
 * ── 이 클래스는 '지금의 폼'이 아니라 '초기값'이다 ─────────────
 * 시드는 멱등해서 폼이 이미 있으면 아무것도 하지 않는다. 운영진이 회차마다 문구를 고치거나
 * 문항을 더하면 DB의 폼과 이 파일은 갈라지고, 그것이 정상이다 — 잠기는 것은 아래
 * MIGRATION_REQUIRED_QITEM_IDS뿐이다(#140 BR-M33: 삭제와 계약 위반만 막고 나머지는 연다).
 */
public final class ProposalFormSeed {

    /** 코드가 이 폼을 찾는 유일한 열쇠 (#140). form_id도 제목도 라벨도 아니다 */
    public static final String SYSTEM_FORM_CODE = "PROPOSAL";

    /*
     * 폼 제목과 라벨. 둘 다 운영진이 화면에서 바꿀 수 있는 표시 데이터라 코드가 판정에 쓰지
     * 않는다 — 여기 있는 것은 시드가 처음 넣는 값일 뿐이다. 라벨을 굳이 함께 시드하는 것은
     * 폼 목록에서 기획안 폼을 눈으로 찾는 유일한 단서가 라벨이기 때문이다.
     */
    public static final String FORM_TITLE = "스터디·프로젝트 기획안";

    public static final String LABEL_NAME = "기획안";

    /*
     * ── qitemId ────────────────────────────────────────────────
     * 표기는 캐멀케이스다. 컬럼명(goal_cn)이 아니라 뜻을 적는다 — 이관 대상 컬럼명을 그대로
     * 쓰면 학술 도메인이 컬럼을 개명하는 순간 응답 key와 뜻이 어긋나는데, 응답 key는 그때
     * 이미 바꿀 수 없는 값이다. 반대로 화면 문구(qitemLblNm)를 키로 삼지 않는 것도 같은 이유다.
     */

    /** 유형(스터디/프로젝트) → academic_program_type_cd */
    public static final String QITEM_PROGRAM_TYPE = "programType";

    /** 활동명 → event.event_ttl */
    public static final String QITEM_PROGRAM_TITLE = "programTitle";

    /** 활동 소개·목표 → academic_program.goal_cn */
    public static final String QITEM_GOAL_CONTENT = "goalContent";

    /** 준비물·사전 요구사항 → academic_program.prep_cn */
    public static final String QITEM_PREP_CONTENT = "prepContent";

    /** 활동 기간 시작 → event.event_bgng_dt */
    public static final String QITEM_PERIOD_BEGIN_DATE = "periodBeginDate";

    /** 활동 기간 종료 → event.event_end_dt */
    public static final String QITEM_PERIOD_END_DATE = "periodEndDate";

    /** 정기 일정 → academic_program.schedule_txt */
    public static final String QITEM_SCHEDULE_TEXT = "scheduleText";

    /** 모집 정원 하한 → academic_program.cpcty_min_cnt */
    public static final String QITEM_CAPACITY_MIN_COUNT = "capacityMinCount";

    /** 모집 정원 상한 → academic_program.cpcty_max_cnt */
    public static final String QITEM_CAPACITY_MAX_COUNT = "capacityMaxCount";

    /** 희망 장소 → event.plc_nm */
    public static final String QITEM_PLACE_NAME = "placeName";

    /** 회차별 커리큘럼 → curriculum_item[] (seqno · ttl · plan_dt) */
    public static final String QITEM_CURRICULUM = "curriculum";

    /*
     * ── 커리큘럼 줄 포맷 ───────────────────────────────────────
     * **제출자가 읽는 안내가 곧 파서의 명세다.** 두 문장을 다른 곳에 적으면 반드시 갈리고,
     * 갈린 순간 제출자는 안내대로 적었는데 승인이 400으로 막힌다. 그래서 아래 상수 하나가
     * 문항 문구(qitemLblNm)에 그대로 들어가고, 이관(#150)의 파서도 이 상수를 근거로 쓴다.
     *
     * 구분자가 '|'인 것은 활동 주제에 쉼표·하이픈·콜론이 흔히 들어가기 때문이다(예:
     * "React, 그리고 상태관리"). 탭은 눈에 보이지 않아 붙여넣기에서 공백으로 바뀐다.
     * 날짜는 맨 뒤에 두고 생략할 수 있게 뒀다 — 접수 시점에 회차별 날짜까지 정해 둔 기획안은
     * 드물고, 없다고 반려할 값이 아니다(curriculum_item.plan_dt도 NULL 허용이다).
     *
     * 자유 텍스트를 그대로 받는 것은 확정된 결정이다(ssccops#131). 접수 단계에서 표로 강제하면
     * 아직 다듬어지지 않은 계획을 칸에 맞춰 적게 되고, 반려될 기획안에도 구조화 비용이 붙는다 —
     * 구조화는 승인 시점(#150)에 한다. 그래서 이 문항에는 정규식(ptrnCn)을 걸지 않는다.
     * 형식이 어긋난 줄은 제출을 막는 대신 검토 화면이 보여주고 수정요청(#141)으로 돌린다.
     */
    public static final String CURRICULUM_LINE_FORMAT = "1회차 | 주제 | 2026-03-05";

    public static final String CURRICULUM_LABEL =
            "회차별 커리큘럼 — 한 줄에 한 회차씩 \"" + CURRICULUM_LINE_FORMAT + "\" 형식으로 적어 주세요 (날짜는 생략할 수 있습니다)";

    /*
     * ── 유형 선택지 ────────────────────────────────────────────
     * academic_program_type 기준정보의 type_nm과 **글자 하나까지** 같아야 한다(현재 시드는
     * STUDY = '스터디', PROJECT = '프로젝트'). 응답은 문자열로 저장되는데 이관(#150)은 그것을
     * 코드로 되돌려야 하므로, 이름이 갈리면 매핑이 끊긴다.
     *
     * 시드가 기준정보를 조회해 선택지를 만들지 않는 것은 의도된 것이다. 그렇게 하면 폼의
     * 문항 구성이 기동 시점의 DB 상태에 따라 달라져, 어느 환경에 무엇이 들어갔는지 코드만 보고
     * 알 수 없다. 대신 ProposalFormSeedTest가 두 값이 같은지 못 박는다 — 기준정보의 이름을
     * 바꾸면 그 테스트가 빨개져 매핑이 끊긴 사실이 배포 전에 드러난다.
     */
    public static final List<String> PROGRAM_TYPE_OPTIONS = List.of("스터디", "프로젝트");

    /*
     * ── 코드가 잠그는 문항 (SystemFormContract에 실린다) ───────
     * **없으면 승인 시 이관이 성립하지 않는 문항만 넣는다.** 선택 문항(준비물·정기 일정·정원·
     * 장소)은 이관이 읽기는 하지만 비어 있어도 되는 값이라, 문항이 사라진 것과 제출자가 비워 둔
     * 것의 결과가 같다 — 잠글 이유가 없고 잠그면 운영진이 회차마다 폼을 다듬을 여지만 없앤다.
     * 반대로 여기 있는 여섯 개는 사라지면 이관이 채울 수 없는 NOT NULL 값(유형·활동명·목표·
     * 기간·커리큘럼)이 되어, 접수는 되는데 승인은 영원히 안 되는 기획안이 쌓인다.
     */
    public static final Set<String> MIGRATION_REQUIRED_QITEM_IDS =
            Set.of(
                    QITEM_PROGRAM_TYPE,
                    QITEM_PROGRAM_TITLE,
                    QITEM_GOAL_CONTENT,
                    QITEM_PERIOD_BEGIN_DATE,
                    QITEM_PERIOD_END_DATE,
                    QITEM_CURRICULUM);

    /*
     * 정원 입력 검증 정규식. QuestionItemType에 숫자 유형이 없어(SHORT_TEXT · LONG_TEXT ·
     * SINGLE_CHOICE · MULTI_CHOICE · DATE 다섯 뿐이다) 단문에 정규식을 걸어 숫자로 좁힌다.
     * enum에 NUMBER를 더하는 쪽은 이 이슈에서 하지 않았다 — 그 어휘는 웹
     * shared/config/codes.ts의 QitemTypeCd와 같아야 하는 계약이라(QuestionItemType 주석),
     * 서버만 먼저 늘리면 편집기가 그릴 수 없는 유형이 폼에 들어간다.
     *
     * 응답 검증(ResponseAnswerValidator)이 matches()가 아니라 find()로 보므로 앵커를 직접 건다.
     */
    private static final String DIGITS_PATTERN = "^[0-9]+$";

    private static final String DIGITS_PATTERN_NAME = "숫자";

    private static final String DIGITS_PATTERN_MESSAGE = "숫자만 입력해 주세요";

    private static final String PAGE_TITLE = "기획안";

    private static final String PAGE_DESCRIPTION =
            "스터디·프로젝트 기획안을 제출합니다. 승인되면 여기에 적은 내용이 그대로 학술 활동으로"
                    + " 옮겨지므로, 활동명·기간·커리큘럼은 실제 운영할 계획대로 적어 주세요.";

    /** 페이지 하나짜리 폼이라 모든 문항이 0번 페이지에 있다 */
    private static final int SINGLE_PAGE_SEQ = 0;

    private ProposalFormSeed() {}

    /*
     * 시드가 넣을 문항 구성.
     *
     * 값을 QuestionCompositionValidator가 정규화한 뒤의 모양 그대로 만든다(비선택형의
     * optionList는 null이 아니라 빈 리스트, 유형에 맞지 않는 속성은 null). 정규화 전 모양으로
     * 만들면 첫 저장·첫 편집에서 구성이 '바뀐 것'으로 판정돼 qitem_ver가 이유 없이 2가 되고
     * (FormEntity.update의 record equals 비교), 아무도 손대지 않은 폼에 이력 행이 하나 더 쌓인다.
     * ProposalFormSeedTest가 validate() 결과와 이 값이 같은지 못 박는다.
     */
    public static QuestionCompositionContent composition() {
        return new QuestionCompositionContent(
                List.of(new Page(PAGE_TITLE, PAGE_DESCRIPTION)),
                List.of(
                        choice(QITEM_PROGRAM_TYPE, "유형", true, PROGRAM_TYPE_OPTIONS),
                        text(QITEM_PROGRAM_TITLE, "활동명", QuestionItemType.SHORT_TEXT, true),
                        text(QITEM_GOAL_CONTENT, "활동 소개·목표", QuestionItemType.LONG_TEXT, true),
                        text(QITEM_PREP_CONTENT, "준비물·사전 요구사항", QuestionItemType.LONG_TEXT, false),
                        date(QITEM_PERIOD_BEGIN_DATE, "활동 기간 시작", true),
                        date(QITEM_PERIOD_END_DATE, "활동 기간 종료", true),
                        text(
                                QITEM_SCHEDULE_TEXT,
                                "정기 일정 (예: 매주 화요일 19:00)",
                                QuestionItemType.SHORT_TEXT,
                                false),
                        digits(QITEM_CAPACITY_MIN_COUNT, "모집 정원 하한 (명)"),
                        digits(QITEM_CAPACITY_MAX_COUNT, "모집 정원 상한 (명)"),
                        text(QITEM_PLACE_NAME, "희망 장소", QuestionItemType.SHORT_TEXT, false),
                        text(
                                QITEM_CURRICULUM,
                                CURRICULUM_LABEL,
                                QuestionItemType.LONG_TEXT,
                                true)));
    }

    private static QuestionItem choice(
            String qitemId, String label, boolean required, List<String> optionList) {
        return new QuestionItem(
                qitemId,
                label,
                QuestionItemType.SINGLE_CHOICE,
                required,
                SINGLE_PAGE_SEQ,
                optionList,
                null,
                null,
                null,
                null,
                null);
    }

    private static QuestionItem text(
            String qitemId, String label, QuestionItemType type, boolean required) {
        return new QuestionItem(
                qitemId,
                label,
                type,
                required,
                SINGLE_PAGE_SEQ,
                List.of(),
                null,
                null,
                null,
                null,
                null);
    }

    private static QuestionItem date(String qitemId, String label, boolean required) {
        return new QuestionItem(
                qitemId,
                label,
                QuestionItemType.DATE,
                required,
                SINGLE_PAGE_SEQ,
                List.of(),
                null,
                null,
                null,
                null,
                null);
    }

    private static QuestionItem digits(String qitemId, String label) {
        return new QuestionItem(
                qitemId,
                label,
                QuestionItemType.SHORT_TEXT,
                false,
                SINGLE_PAGE_SEQ,
                List.of(),
                null,
                DIGITS_PATTERN,
                DIGITS_PATTERN_NAME,
                DIGITS_PATTERN_MESSAGE,
                null);
    }
}
