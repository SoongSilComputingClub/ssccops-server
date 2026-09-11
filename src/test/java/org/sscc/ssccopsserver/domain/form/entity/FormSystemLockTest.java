package org.sscc.ssccopsserver.domain.form.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sscc.ssccopsserver.domain.form.code.QuestionItemType;
import org.sscc.ssccopsserver.domain.form.code.error.FormErrorCode;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent.Page;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent.QuestionItem;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/*
 * 시스템 폼 잠금과 문항 구성 버전 규칙 (#140) — 엔티티 단위.
 *
 * 스프링을 띄우지 않는 것은 여기서 확인하려는 것이 배선이 아니라 판정 자체이기 때문이다.
 * 배선(컨트롤러 → 서비스 → 엔티티)은 FormControllerTest가 실제 요청으로 확인한다.
 *
 * 삭제 잠금(requireDeletable)을 여기서만 확인하는 것은 **폼 삭제 API가 아직 없어서다.**
 * 그 경로가 생기면 이 판정을 그대로 부르면 되고, 잠금 규칙을 그 이슈에서 새로 적으면
 * 규칙이 두 벌이 된다 (FormEntity.requireDeletable 주석).
 */
class FormSystemLockTest {

    private static final Set<String> CONTRACT = Set.of("q1");

    /* ── 시스템 폼 잠금 ───────────────────────────────────── */

    // 평범한 운영 폼은 잠기지 않는다. 잠금이 기본값이 되면 폼을 아무도 지울 수 없다
    @Test
    void ordinaryFormIsDeletable() {
        FormEntity form = form(composition("q1", "q2"));

        assertThat(form.isSystemForm()).isFalse();
        assertThatCode(form::requireDeletable).doesNotThrowAnyException();
    }

    /*
     * 코드가 sys_form_cd로 직접 가리키는 폼은 지워지는 순간 그 폼을 찾는 기능이 통째로 무너진다.
     * 화면 조작 한 번으로 도달할 수 있는 손실이라 서버가 막는다.
     */
    @Test
    void systemFormIsNotDeletable() {
        FormEntity form = form(composition("q1", "q2"));
        form.designateAsSystemForm("PROPOSAL");

        assertThat(form.isSystemForm()).isTrue();
        assertThat(form.getSystemFormCode()).isEqualTo("PROPOSAL");
        assertThatThrownBy(form::requireDeletable)
                .isInstanceOf(GeneralException.class)
                .extracting(thrown -> ((GeneralException) thrown).getErrorCode())
                .isEqualTo(FormErrorCode.SYSTEM_FORM_IMMUTABLE);
    }

    /* ── 코드 계약 ────────────────────────────────────────── */

    // 코드가 그 qitemId로 값을 읽으므로, 지우면 조용히 빈 값이 읽힌다 — 터지지 않고 틀리는 종류다
    @Test
    void systemFormRejectsRemovingContractQuestionItem() {
        FormEntity form = form(composition("q1", "q2"));
        form.designateAsSystemForm("PROPOSAL");

        QuestionCompositionContent withoutQ1 = composition("q2");

        assertThatThrownBy(() -> form.requireSystemContractKept(withoutQ1, CONTRACT))
                .isInstanceOf(GeneralException.class)
                .extracting(thrown -> ((GeneralException) thrown).getErrorCode())
                .isEqualTo(FormErrorCode.SYSTEM_FORM_CONTRACT_VIOLATION);
    }

    /*
     * 문항 추가·순서 변경은 계약을 깨지 않는다. 막으면 시스템 폼은 한 번 세운 뒤 아무도
     * 고칠 수 없는 폼이 되고, 운영진이 회차마다 문항을 더하는 실제 사용을 그대로 막는다.
     */
    @Test
    void systemFormAllowsAddingAndReorderingAroundTheContract() {
        FormEntity form = form(composition("q1", "q2"));
        form.designateAsSystemForm("PROPOSAL");

        assertThatCode(
                        () ->
                                form.requireSystemContractKept(
                                        composition("q3", "q2", "q1"), CONTRACT))
                .doesNotThrowAnyException();
    }

    /*
     * 시스템 폼이 아니면 계약 검사 자체가 없다 — 요구 목록이 선언돼 있어도 마찬가지다.
     * 계약은 코드가 그 폼을 가리킬 때만 성립한다.
     */
    @Test
    void ordinaryFormIgnoresTheContract() {
        FormEntity form = form(composition("q1", "q2"));

        assertThatCode(() -> form.requireSystemContractKept(composition("q2"), CONTRACT))
                .doesNotThrowAnyException();
    }

    /*
     * 계약이 비어 있는 시스템 폼은 문항을 자유롭게 고칠 수 있다. 시스템 폼이라는 표시와
     * 요구 문항의 존재는 별개이며, 계약이 없다고 삭제 잠금까지 풀리지는 않는다.
     */
    @Test
    void systemFormWithoutContractCanChangeEveryQuestionItem() {
        FormEntity form = form(composition("q1", "q2"));
        form.designateAsSystemForm("PROPOSAL");

        assertThatCode(() -> form.requireSystemContractKept(composition("other"), Set.of()))
                .doesNotThrowAnyException();
        assertThatThrownBy(form::requireDeletable).isInstanceOf(GeneralException.class);
    }

    /* ── 문항 구성 버전 ──────────────────────────────────── */

    @Test
    void newFormStartsAtVersionOne() {
        assertThat(form(composition("q1")).getQuestionVersion()).isEqualTo(1);
    }

    /*
     * 편집 자동 저장(ssccops #63)은 매 타이핑마다 PUT을 쏜다. 무조건 올리면 제목 한 글자를
     * 고치는 동안 버전이 수백까지 뛰고 그만큼의 이력이 쌓여 되짚는 데 쓸모가 없어진다.
     */
    @Test
    void versionStaysWhenOnlyTitleAndPeriodChange() {
        FormEntity form = form(composition("q1", "q2"));

        boolean bumped = form.update("바뀐 제목", composition("q1", "q2"), null, null, false);

        assertThat(bumped).isFalse();
        assertThat(form.getQuestionVersion()).isEqualTo(1);
        assertThat(form.getTitle()).isEqualTo("바뀐 제목");
    }

    // 문항이 실제로 바뀐 저장에서만 오른다. 올랐다는 사실은 이력을 남길 근거로 호출부가 받는다
    @Test
    void versionRisesWhenCompositionChanges() {
        FormEntity form = form(composition("q1", "q2"));

        assertThat(form.update("제목", composition("q1", "q2", "q3"), null, null, false)).isTrue();
        assertThat(form.getQuestionVersion()).isEqualTo(2);

        assertThat(form.update("제목", composition("q1", "q2", "q3"), null, null, false)).isFalse();
        assertThat(form.getQuestionVersion()).isEqualTo(2);
    }

    /*
     * 문구만 바꾼 것도 구성 변경이다. qitemId 집합이 같아도 응답자가 보는 질문이 달라졌으므로,
     * 그 시점의 문구를 이력에 남겨야 "무엇을 보고 답했는가"에 답할 수 있다.
     */
    @Test
    void versionRisesWhenOnlyTheLabelOfAQuestionItemChanges() {
        FormEntity form = form(composition("q1"));
        QuestionCompositionContent renamed =
                new QuestionCompositionContent(
                        List.of(new Page("한 장", null)),
                        List.of(
                                new QuestionItem(
                                        "q1",
                                        "다시 쓴 질문",
                                        QuestionItemType.SHORT_TEXT,
                                        false,
                                        0,
                                        List.of(),
                                        null,
                                        null,
                                        null,
                                        null,
                                        null)));

        assertThat(form.update("제목", renamed, null, null, false)).isTrue();
        assertThat(form.getQuestionVersion()).isEqualTo(2);
    }

    private FormEntity form(QuestionCompositionContent composition) {
        return FormEntity.create(null, "표본 폼", composition, null, null);
    }

    /** 지정한 qitemId를 순서대로 담은 한 페이지짜리 구성 */
    private QuestionCompositionContent composition(String... qitemIds) {
        return new QuestionCompositionContent(
                List.of(new Page("한 장", null)),
                List.of(qitemIds).stream()
                        .map(
                                qitemId ->
                                        new QuestionItem(
                                                qitemId,
                                                qitemId + " 문항",
                                                QuestionItemType.SHORT_TEXT,
                                                false,
                                                0,
                                                List.of(),
                                                null,
                                                null,
                                                null,
                                                null,
                                                null))
                        .toList());
    }
}
