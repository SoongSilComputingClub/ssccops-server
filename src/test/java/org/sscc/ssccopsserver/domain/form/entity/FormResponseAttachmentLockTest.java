package org.sscc.ssccopsserver.domain.form.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.sscc.ssccopsserver.domain.form.code.QuestionItemType;
import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.code.error.FormErrorCode;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent.Page;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent.QuestionItem;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/*
 * 기획안 첨부의 상태 잠금 표 (server#685 · FormResponseHistoryEntity.requireAttachmentsEditable).
 *
 * 열리는 칸은 둘이다 — DRAFT(폼이 접수 중) · CHANGES_REQUESTED(접수와 무관). 나머지는 전부 409
 * PROPOSAL_ATTACHMENT_LOCKED이며, 접수가 끝난 폼의 초안만 문장이 다르다. 컨텍스트 없이 엔티티만 본다 —
 * 표가 엔티티에 있어야 하는 이유(상태 어휘가 늘 때 서비스마다 옮겨 적지 않는다)를 이 테스트가 지킨다.
 */
class FormResponseAttachmentLockTest {

    @Test
    @DisplayName("작성 중(DRAFT)은 폼이 접수 중일 때만 첨부를 바꿀 수 있다")
    void draftIsEditableOnlyWhileTheFormAccepts() {
        FormResponseHistoryEntity draft = responseIn(ResponseStatus.DRAFT);

        assertThatCode(() -> draft.requireAttachmentsEditable(true)).doesNotThrowAnyException();
        assertThatThrownBy(() -> draft.requireAttachmentsEditable(false))
                .isInstanceOfSatisfying(
                        GeneralException.class,
                        e -> {
                            assertThat(e.getErrorCode())
                                    .isEqualTo(FormErrorCode.PROPOSAL_ATTACHMENT_LOCKED);
                            // 기본 문장(«수정요청을 받았을 때만»)은 제출한 응답을 향한 말이라 갈아 끼운다
                            assertThat(e.getDetail()).contains("접수가 끝난");
                        });
    }

    @Test
    @DisplayName("수정요청(CHANGES_REQUESTED)은 접수가 끝났어도 첨부를 바꿀 수 있다 — 재제출과 같은 예외다")
    void changesRequestedIsEditableRegardlessOfReceipt() {
        FormResponseHistoryEntity changesRequested = responseIn(ResponseStatus.CHANGES_REQUESTED);

        assertThatCode(() -> changesRequested.requireAttachmentsEditable(true))
                .doesNotThrowAnyException();
        assertThatCode(() -> changesRequested.requireAttachmentsEditable(false))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(
            value = ResponseStatus.class,
            names = {"SUBMITTED", "ACCEPTED", "REJECTED"})
    @DisplayName("검토 중·승인·반려는 접수 여부와 무관하게 409 — 검토자가 본 것과 승인한 것이 갈리지 않게")
    void submittedAndConcludedResponsesAreLocked(ResponseStatus status) {
        FormResponseHistoryEntity response = responseIn(status);

        for (boolean accepting : List.of(true, false)) {
            assertThatThrownBy(() -> response.requireAttachmentsEditable(accepting))
                    .isInstanceOfSatisfying(
                            GeneralException.class,
                            e -> {
                                assertThat(e.getErrorCode())
                                        .isEqualTo(FormErrorCode.PROPOSAL_ATTACHMENT_LOCKED);
                                assertThat(e.getDetail()).isNull();
                            });
        }
    }

    /* 상태는 정상 경로(제출 → 검토 전이)로만 만든다 — 테스트 전용 생성 경로가 전이표를 비껴가지 않게 */
    private static FormResponseHistoryEntity responseIn(ResponseStatus status) {
        FormEntity form = FormEntity.create(null, "기획안", oneQuestion(), null, null);
        ResponseContent content = ResponseContent.of(Map.of("q1", "스터디"));
        if (status == ResponseStatus.DRAFT) {
            return FormResponseHistoryEntity.createDraft(form, null, content);
        }
        FormResponseHistoryEntity response =
                FormResponseHistoryEntity.createSubmitted(
                        form, null, content, Instant.parse("2026-10-01T00:00:00Z"));
        if (status != ResponseStatus.SUBMITTED) {
            response.changeStatus(status);
        }
        return response;
    }

    private static QuestionCompositionContent oneQuestion() {
        return new QuestionCompositionContent(
                List.of(new Page("기획안", null)),
                List.of(
                        new QuestionItem(
                                "q1",
                                "활동명",
                                QuestionItemType.SHORT_TEXT,
                                false,
                                0,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null)));
    }
}
