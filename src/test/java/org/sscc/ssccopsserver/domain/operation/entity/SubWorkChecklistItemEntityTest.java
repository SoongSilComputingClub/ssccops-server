package org.sscc.ssccopsserver.domain.operation.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SubWorkChecklistItemEntityTest {

    private SubWorkChecklistItemEntity item() {
        return SubWorkChecklistItemEntity.create(null, "장소 후보 3곳 리스트업", 1);
    }

    // 등록 직후에는 모두 미완료다 — 완료 조건을 미리 충족시킨 채로 태어나지 않는다
    @Test
    void newItemIsNotCompleted() {
        assertThat(item().isCompleted()).isFalse();
    }

    @Test
    void updateCompletionChecksAndUnchecks() {
        SubWorkChecklistItemEntity item = item();

        item.updateCompletion(true);
        assertThat(item.isCompleted()).isTrue();

        // 해제도 같은 메서드다. 되돌리기 전용 경로를 두지 않는다
        item.updateCompletion(false);
        assertThat(item.isCompleted()).isFalse();
    }

    // 같은 값을 다시 넣어도 결과가 같다 — 더블 탭이 상태를 두 칸 밀지 않아 멱등성 키가 필요 없다
    @Test
    void updateCompletionIsIdempotent() {
        SubWorkChecklistItemEntity item = item();

        item.updateCompletion(true);
        item.updateCompletion(true);

        assertThat(item.isCompleted()).isTrue();
    }

    // 체크는 문구·순서를 건드리지 않는다. 문구는 전용 메서드(changeArticle)만 바꿈 수 있다
    @Test
    void updateCompletionDoesNotTouchArticleOrOrder() {
        SubWorkChecklistItemEntity item = item();

        item.updateCompletion(true);

        assertThat(item.getArticle()).isEqualTo("장소 후보 3곳 리스트업");
        assertThat(item.getSortOrder()).isEqualTo(1);
    }

    /*
     * 문구 수정이 열렸다 (#307). 잠그고 있던 근거는 사라진 것이 아니라 상태 잠금
     * (SubWorkEntity.requireChecklistItemEditable)·체크된 항목 삭제 금지·이력으로 옥겨 갔다.
     */
    @Test
    void changeArticleReplacesTheArticle() {
        SubWorkChecklistItemEntity item = item();

        item.changeArticle("장소 후보 5곳 리스트업");

        assertThat(item.getArticle()).isEqualTo("장소 후보 5곳 리스트업");
    }

    /*
     * 문구를 고쳐도 체크 상태는 그대로다 — 문구를 다듬는 것과 그 항목을 해낸 것은 다른
     * 사실이다. 순서도 그대로다 — 수정이 목록에서 자리를 옮기지 않는다.
     */
    @Test
    void changeArticleKeepsCompletionAndOrder() {
        SubWorkChecklistItemEntity item = item();
        item.updateCompletion(true);

        item.changeArticle("장소 후보 5곳 리스트업");

        assertThat(item.isCompleted()).isTrue();
        assertThat(item.getSortOrder()).isEqualTo(1);
    }
}
