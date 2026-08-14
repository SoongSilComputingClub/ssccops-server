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

    // 항목 내용·순서는 유형에서 복사된 값이라 바뀌지 않는다 (완료 조건을 사후에 낮추지 못한다)
    @Test
    void updateCompletionDoesNotTouchArticleOrOrder() {
        SubWorkChecklistItemEntity item = item();

        item.updateCompletion(true);

        assertThat(item.getArticle()).isEqualTo("장소 후보 3곳 리스트업");
        assertThat(item.getSortOrder()).isEqualTo(1);
    }
}
