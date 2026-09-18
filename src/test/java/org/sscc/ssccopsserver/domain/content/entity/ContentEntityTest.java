package org.sscc.ssccopsserver.domain.content.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.sscc.ssccopsserver.domain.content.code.ContentCategory;
import org.sscc.ssccopsserver.domain.content.code.ContentPublishStatus;
import org.sscc.ssccopsserver.domain.content.code.error.ContentErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/*
 * 페이지·포스트의 상태 전이 (ssccops#381). 전이표는 엔티티가 갖고 서비스는 조회·이력·감사만
 * 한다 — 그래서 여기서 컨텍스트 없이 확인한다. 변경자(MemberEntity)는 null로 넘긴다: 이 테스트가
 * 보는 것은 상태와 시각이고 엔티티는 변경자를 검사하지 않는다.
 */
class ContentEntityTest {

    private static final Instant PUBLISHED_AT = Instant.parse("2026-09-19T03:00:00Z");

    @Test
    @DisplayName("페이지는 초안으로 태어나고 게시하면 게시 시각이 실린다")
    void pageStartsAsDraftAndPublishStampsTime() {
        ContentPageEntity page = ContentPageEntity.create("about", "소개", "# 소개", null);

        assertThat(page.getPublishStatus()).isEqualTo(ContentPublishStatus.DRAFT);
        assertThat(page.getPublishedAt()).isNull();
        assertThat(page.isPublished()).isFalse();

        page.publish(PUBLISHED_AT, null);

        assertThat(page.getPublishStatus()).isEqualTo(ContentPublishStatus.PUBLISHED);
        assertThat(page.getPublishedAt()).isEqualTo(PUBLISHED_AT);
        assertThat(page.isPublished()).isTrue();
    }

    @Test
    @DisplayName("게시 취소는 초안으로 돌리고 게시 시각을 비운다 — 옛 시각은 이력의 몫이다")
    void unpublishReturnsToDraftAndClearsTime() {
        ContentPageEntity page = ContentPageEntity.create("about", "소개", "# 소개", null);
        page.publish(PUBLISHED_AT, null);

        page.unpublish(null);

        assertThat(page.getPublishStatus()).isEqualTo(ContentPublishStatus.DRAFT);
        assertThat(page.getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("같은 상태로의 전이는 409다 — 두 운영자가 같은 버튼을 동시에 누른 경우")
    void repeatedTransitionsAreConflicts() {
        ContentPageEntity page = ContentPageEntity.create("about", "소개", "# 소개", null);

        assertThatThrownBy(() -> page.unpublish(null))
                .isInstanceOf(GeneralException.class)
                .extracting("errorCode")
                .isEqualTo(ContentErrorCode.CONTENT_NOT_PUBLISHED);

        page.publish(PUBLISHED_AT, null);

        assertThatThrownBy(() -> page.publish(PUBLISHED_AT.plusSeconds(1), null))
                .isInstanceOf(GeneralException.class)
                .extracting("errorCode")
                .isEqualTo(ContentErrorCode.CONTENT_ALREADY_PUBLISHED);
        // 거절된 전이는 아무것도 바꾸지 않는다
        assertThat(page.getPublishedAt()).isEqualTo(PUBLISHED_AT);
    }

    @Test
    @DisplayName("수정은 게시 상태를 건드리지 않는다 — 상태를 바꾸는 길은 publish/unpublish뿐이다")
    void updateLeavesPublishStatusAlone() {
        ContentPageEntity page = ContentPageEntity.create("about", "소개", "# 소개", null);
        page.publish(PUBLISHED_AT, null);

        page.update("about-us", "소개(개정)", "# 소개 v2", null);

        assertThat(page.getSlug()).isEqualTo("about-us");
        assertThat(page.getTitle()).isEqualTo("소개(개정)");
        assertThat(page.getPublishStatus()).isEqualTo(ContentPublishStatus.PUBLISHED);
        assertThat(page.getPublishedAt()).isEqualTo(PUBLISHED_AT);
    }

    @Test
    @DisplayName("포스트도 같은 전이표를 쓰고, 표지는 생성 시점에 없다")
    void postFollowsTheSameTransitionsAndHasNoCoverAtBirth() {
        ContentPostEntity post =
                ContentPostEntity.create(
                        "homecoming-2026",
                        ContentCategory.EVENT,
                        "홈커밍",
                        null,
                        "# 홈커밍",
                        LocalDate.of(2026, 9, 12),
                        7L,
                        null);

        assertThat(post.getPublishStatus()).isEqualTo(ContentPublishStatus.DRAFT);
        assertThat(post.getCoverFileId()).isNull();
        assertThat(post.getEventId()).isEqualTo(7L);

        post.publish(PUBLISHED_AT, null);
        assertThat(post.isPublished()).isTrue();

        assertThatThrownBy(() -> post.publish(PUBLISHED_AT, null))
                .isInstanceOf(GeneralException.class)
                .extracting("errorCode")
                .isEqualTo(ContentErrorCode.CONTENT_ALREADY_PUBLISHED);

        post.unpublish(null);
        assertThat(post.getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("갤러리에서 표지를 지우면 표지가 비고, 다른 장을 지우면 그대로다")
    void clearingCoverOnlyWhenTheDeletedFileIsTheCover() {
        ContentPostEntity post =
                ContentPostEntity.create(
                        "p", ContentCategory.NEWS, "t", null, "b", LocalDate.EPOCH, null, null);
        post.update("p", ContentCategory.NEWS, "t", null, "b", LocalDate.EPOCH, null, 42L, null);

        post.clearCoverIfMatches(41L);
        assertThat(post.getCoverFileId()).isEqualTo(42L);

        post.clearCoverIfMatches(42L);
        assertThat(post.getCoverFileId()).isNull();
    }

    @Test
    @DisplayName("이력 스냅샷은 변경 뒤의 모습을 그대로 찍는다")
    void historySnapshotCapturesTheStateAfterChange() {
        ContentPageEntity page = ContentPageEntity.create("about", "소개", "# 소개", null);
        page.publish(PUBLISHED_AT, null);

        ContentPageHistoryEntity history =
                ContentPageHistoryEntity.snapshotOf(page, null, PUBLISHED_AT);

        assertThat(history.getTitle()).isEqualTo("소개");
        assertThat(history.getBody()).isEqualTo("# 소개");
        assertThat(history.getPublishStatus()).isEqualTo(ContentPublishStatus.PUBLISHED);
        assertThat(history.getChangedAt()).isEqualTo(PUBLISHED_AT);
        assertThat(history.getPage()).isSameAs(page);
    }
}
