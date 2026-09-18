package org.sscc.ssccopsserver.domain.content.entity;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.sscc.ssccopsserver.domain.content.code.ContentCategory;
import org.sscc.ssccopsserver.domain.content.code.ContentPublishStatus;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * cntnt_post_hstry(포스트 개정 이력) — 변경 뒤 전체 스냅샷 한 행 (ssccops#381 · V15).
 * 규칙은 ContentPageHistoryEntity와 같다(전 컬럼 updatable = false · 삭제 없음 · 구분 코드
 * 없음). 표지·행사 id·갤러리는 싣지 않는다 — 이력이 지키려는 것은 «무엇이 쓰여 있었나»이고
 * 파일 참조는 그 시점에 실물이 있었다는 보장이 애초에 없다.
 */
@Entity
@Table(
        name = "cntnt_post_hstry",
        indexes = @Index(name = "idx_cntnt_post_hstry_post_id", columnList = "post_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ContentPostHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "post_hstry_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false, updatable = false)
    private ContentPostEntity post;

    @Enumerated(EnumType.STRING)
    @Column(name = "cntnt_clsf_cd", nullable = false, length = 20, updatable = false)
    private ContentCategory category;

    @Column(name = "ttl", nullable = false, length = 200, updatable = false)
    private String title;

    @Column(name = "smry", length = 300, updatable = false)
    private String summary;

    @Column(name = "mtxt", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String body;

    @Column(name = "actv_ymd", nullable = false, updatable = false)
    private LocalDate activityDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "pub_stts_cd", nullable = false, length = 20, updatable = false)
    private ContentPublishStatus publishStatus;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chg_mbr_id", nullable = false, updatable = false)
    private MemberEntity changer;

    @Column(name = "chg_dt", nullable = false, updatable = false)
    private Instant changedAt;

    public static ContentPostHistoryEntity snapshotOf(
            ContentPostEntity post, MemberEntity changer, Instant changedAt) {
        return new ContentPostHistoryEntity(
                null,
                post,
                post.getCategory(),
                post.getTitle(),
                post.getSummary(),
                post.getBody(),
                post.getActivityDate(),
                post.getPublishStatus(),
                changer,
                changedAt);
    }
}
