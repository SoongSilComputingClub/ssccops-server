package org.sscc.ssccopsserver.domain.content.entity;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
import jakarta.persistence.UniqueConstraint;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.sscc.ssccopsserver.domain.content.code.ContentCategory;
import org.sscc.ssccopsserver.domain.content.code.ContentPublishStatus;
import org.sscc.ssccopsserver.domain.content.code.error.ContentErrorCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * cntnt_post(콘텐츠 포스트) — 활동 아카이브의 한 건 (ssccops#381 · ADR-0038 · V15).
 * 분류(학술·행사·뉴스)·활동일·요약·표지·갤러리를 가진 목록형 문서이며, 페이지와 테이블을
 * 나눈 이유는 ContentPageEntity 주석에 있다.
 *
 * **행사와 파일은 식별자(Long)로만 가리킨다.** event_id·cover_file_id에 FK는 있지만(V15)
 * 엔티티 연관(@ManyToOne EventEntity …)을 두지 않은 것은, 이 도메인이 행사에서 필요로 하는
 * 것이 from-event 복사 한 번뿐이고 조회마다 행사를 끌어올 이유가 없기 때문이다 — 연관을 두면
 * 지운 행사(del_dt)를 보는 규칙까지 이쪽이 알아야 한다. 익명 응답의 eventId는 www가
 * /public/v1/events/{id}로 이어 붙이는 링크 재료일 뿐이다.
 *
 * 활동일(actv_ymd)은 게시일과 다르다 — 지난 학기 행사를 오늘 갈무리해도 목록은 활동일
 * 순서다(익명 목록의 정렬 키이자 커서). 그래서 NOT NULL이다.
 *
 * 표지(cover_file_id)는 갤러리(file_rfrnc · CONTENT_POST · trgt_id = post_id) 중 한 장이다.
 * «갤러리에 있는 파일인가»는 엔티티가 조회할 수 없어 서비스가 본다(COVER_NOT_IN_GALLERY).
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "cntnt_post",
        uniqueConstraints = @UniqueConstraint(name = "uk_cntnt_post_slug", columnNames = "slug"),
        indexes =
                @Index(
                        name = "idx_cntnt_post_pub_list",
                        columnList = "pub_stts_cd, cntnt_clsf_cd, actv_ymd, post_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ContentPostEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "post_id")
    private Long id;

    @Column(name = "slug", nullable = false, length = 80)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(name = "cntnt_clsf_cd", nullable = false, length = 20)
    private ContentCategory category;

    @Column(name = "ttl", nullable = false, length = 200)
    private String title;

    /** 목록 카드의 한두 줄. 없으면 NULL */
    @Column(name = "smry", length = 300)
    private String summary;

    @Column(name = "mtxt", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "actv_ymd", nullable = false)
    private LocalDate activityDate;

    /** 갈무리한 행사(event.event_id). from-event로 만들면 채워지고 수정에서 비울 수 있다 */
    @Column(name = "event_id")
    private Long eventId;

    /** 표지(file_rfrnc.file_rfrnc_id). 갤러리의 한 장이며 그 장을 지우면 함께 비운다 */
    @Column(name = "cover_file_id")
    private Long coverFileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "pub_stts_cd", nullable = false, length = 20)
    private ContentPublishStatus publishStatus;

    @Column(name = "pub_dt")
    private Instant publishedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mdfcn_mbr_id", nullable = false)
    private MemberEntity modifier;

    @CreatedDate
    @Column(name = "reg_dt", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "mdfcn_dt", nullable = false)
    private Instant updatedAt;

    /** 생성. 상태는 언제나 DRAFT다. 표지는 갤러리가 생긴 뒤에야 고를 수 있어 여기서 받지 않는다 */
    public static ContentPostEntity create(
            String slug,
            ContentCategory category,
            String title,
            String summary,
            String body,
            LocalDate activityDate,
            Long eventId,
            MemberEntity modifier) {
        return new ContentPostEntity(
                null,
                slug,
                category,
                title,
                summary,
                body,
                activityDate,
                eventId,
                null,
                ContentPublishStatus.DRAFT,
                null,
                modifier,
                null,
                null);
    }

    /** 수정 — 통째로 교체 (ContentPageEntity.update와 같은 계약). 표지 검증은 서비스가 끝냈다 */
    public void update(
            String slug,
            ContentCategory category,
            String title,
            String summary,
            String body,
            LocalDate activityDate,
            Long eventId,
            Long coverFileId,
            MemberEntity modifier) {
        this.slug = slug;
        this.category = category;
        this.title = title;
        this.summary = summary;
        this.body = body;
        this.activityDate = activityDate;
        this.eventId = eventId;
        this.coverFileId = coverFileId;
        this.modifier = modifier;
    }

    /*
     * 갤러리에서 한 장을 지울 때 그 장이 표지였으면 표지를 비운다. cover_file_id가 FK라
     * 비우지 않으면 file_rfrnc 삭제가 위반으로 터진다 — 이 메서드가 그 순서의 앞에 온다.
     */
    public void clearCoverIfMatches(Long fileId) {
        if (fileId != null && fileId.equals(this.coverFileId)) {
            this.coverFileId = null;
        }
    }

    public void publish(Instant publishedAt, MemberEntity modifier) {
        if (this.publishStatus == ContentPublishStatus.PUBLISHED) {
            throw new GeneralException(ContentErrorCode.CONTENT_ALREADY_PUBLISHED);
        }
        this.publishStatus = ContentPublishStatus.PUBLISHED;
        this.publishedAt = publishedAt;
        this.modifier = modifier;
    }

    public void unpublish(MemberEntity modifier) {
        if (this.publishStatus != ContentPublishStatus.PUBLISHED) {
            throw new GeneralException(ContentErrorCode.CONTENT_NOT_PUBLISHED);
        }
        this.publishStatus = ContentPublishStatus.DRAFT;
        this.publishedAt = null;
        this.modifier = modifier;
    }

    public boolean isPublished() {
        return this.publishStatus == ContentPublishStatus.PUBLISHED;
    }
}
