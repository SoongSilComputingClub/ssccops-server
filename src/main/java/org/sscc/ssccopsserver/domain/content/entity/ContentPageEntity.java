package org.sscc.ssccopsserver.domain.content.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.sscc.ssccopsserver.domain.content.code.ContentPublishStatus;
import org.sscc.ssccopsserver.domain.content.code.error.ContentErrorCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * cntnt_page(콘텐츠 페이지) — 소개·연혁·운영진·FAQ처럼 slug 하나로 찾는 단건 문서
 * (ssccops#381 · Epic ssccops#378 · ADR-0038 · V15).
 *
 * **포스트(ContentPostEntity)와 다른 테이블이다.** 분류·활동일·요약·표지·갤러리는 포스트에만
 * 있고 페이지에는 없다 — 합치면 페이지 행의 절반이 NULL이고 목록 질의마다 «페이지가 아닌 것»
 * 필터가 붙는다(폼 템플릿을 form_tmpl로 나눈 #142와 같은 판단).
 *
 * 본문(mtxt)은 마크다운 원문 그대로다. 서버는 길이(ContentBody.MAX_LENGTH)만 보고 파싱·raw
 * HTML 차단은 www 렌더러가 한다(ADR-0038 «본문 포맷 A» — 작성자가 코드를 실행할 길이 없는
 * 포맷이라는 것이 이 도메인의 보안 경계다).
 *
 * 수정자(mdfcn_mbr_id)는 요청 본문이 아니라 @CurrentMember다. 생성자를 따로 두지 않은 것은
 * 첫 이력 행(chg_mbr_id)이 곧 그 값이기 때문이다.
 *
 * 변경마다 이력(ContentPageHistoryEntity)이 한 행 는다 — 그 규칙은 서비스가 지킨다. 엔티티는
 * 스냅샷을 만들 재료(getter)만 준다.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "cntnt_page",
        uniqueConstraints = @UniqueConstraint(name = "uk_cntnt_page_slug", columnNames = "slug"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ContentPageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "page_id")
    private Long id;

    /** 공개 주소의 식별자 (ContentSlug). 숫자 id를 공개 주소에 쓰지 않는다 */
    @Column(name = "slug", nullable = false, length = 80)
    private String slug;

    @Column(name = "ttl", nullable = false, length = 200)
    private String title;

    @Column(name = "mtxt", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "pub_stts_cd", nullable = false, length = 20)
    private ContentPublishStatus publishStatus;

    /** 마지막으로 게시한 시각. 게시 취소하면 비운다 — 익명 응답의 pubDt가 이 값이다 */
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

    /** 생성. 상태는 언제나 DRAFT다 — 만들자마자 공개되는 경로를 두지 않는다 (행사·폼과 같다) */
    public static ContentPageEntity create(
            String slug, String title, String body, MemberEntity modifier) {
        return new ContentPageEntity(
                null, slug, title, body, ContentPublishStatus.DRAFT, null, modifier, null, null);
    }

    /*
     * 수정 — 통째로 교체다(PATCH이지만 부분이 아니다 · 운영 도메인 F2와 같은 계약). 게시 상태는
     * 받지 않는다 — 상태를 바꾸는 길은 publish/unpublish 둘뿐이다(폼 PUT이 formSttsCd를 무시하는
     * 것과 같은 태도). slug도 바꿀 수 있다: 게시 뒤에 바꾸면 이미 퍼진 링크가 깨지는데, 그것을
     * 막는 것은 서버가 아니라 화면의 확인 문구다 — 오타 난 slug를 영영 못 고치는 것이 더 나쁘다.
     */
    public void update(String slug, String title, String body, MemberEntity modifier) {
        this.slug = slug;
        this.title = title;
        this.body = body;
        this.modifier = modifier;
    }

    /*
     * 게시. 이미 게시돼 있으면 409 — 두 운영자가 같은 버튼을 동시에 누른 경우가 실제 경로라
     * 두 번째 사람이 «이미 됐다»를 알아야 한다. 게시 시각은 인자로 받는다(Clock 주입 · 테스트
     * 고정).
     */
    public void publish(Instant publishedAt, MemberEntity modifier) {
        if (this.publishStatus == ContentPublishStatus.PUBLISHED) {
            throw new GeneralException(ContentErrorCode.CONTENT_ALREADY_PUBLISHED);
        }
        this.publishStatus = ContentPublishStatus.PUBLISHED;
        this.publishedAt = publishedAt;
        this.modifier = modifier;
    }

    /*
     * 게시 취소 — 초안으로 돌아간다. 보관(ARCHIVED) 같은 셋째 상태를 두지 않았다
     * (ContentPublishStatus 주석). 게시 시각을 비우는 것은 «언제 게시됐나»가 이제 사실이
     * 아니기 때문이다 — 다시 게시하면 그때의 시각이 실린다. 옛 시각은 이력 행이 든다.
     */
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
