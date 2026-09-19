package org.sscc.ssccopsserver.domain.content.entity;

import java.time.Instant;

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

import org.sscc.ssccopsserver.domain.content.code.ContentPublishStatus;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * cntnt_page_hstry(페이지 개정 이력) — 변경 뒤 전체 스냅샷 한 행 (ssccops#381 · V15).
 *
 * **전 컬럼 updatable = false**(이 레포의 이력 관례 · FormResponseReviewHistoryEntity 등)이고
 * 지우는 경로가 없다. bfr_/aftr_ 쌍이 아니라 전체 스냅샷인 것은 개인정보 처리방침 같은
 * 페이지의 «그때 무엇이 게시돼 있었나»가 법적으로 필요한 값이고, 한 행이 그 시점의 본문
 * 전체를 들어야 diff 없이 그대로 읽히기 때문이다. 대가는 저장 용량인데 본문 상한 10만 자 ×
 * 페이지 수십 개 × 개정 횟수라 문제가 되지 않는다.
 *
 * 변경자(chg_mbr_id)는 @CurrentMember이고 변경 시각은 Clock에서 온다. 생성·수정·게시·게시
 * 취소 전부 한 행씩이며 «무엇을 했나»(구분 코드)는 싣지 않는다 — 직전 행과 비교하면 드러나고,
 * 게시·게시 취소는 감사 로그(AuditAction)가 따로 남긴다.
 */
@Entity
@Table(
        name = "cntnt_page_hstry",
        indexes = @Index(name = "idx_cntnt_page_hstry_page_id", columnList = "page_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ContentPageHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "page_hstry_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "page_id", nullable = false, updatable = false)
    private ContentPageEntity page;

    @Column(name = "ttl", nullable = false, length = 200, updatable = false)
    private String title;

    @Column(name = "mtxt", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "pub_stts_cd", nullable = false, length = 20, updatable = false)
    private ContentPublishStatus publishStatus;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chg_mbr_id", nullable = false, updatable = false)
    private MemberEntity changer;

    @Column(name = "chg_dt", nullable = false, updatable = false)
    private Instant changedAt;

    /** 변경 «뒤»의 페이지를 그대로 찍는다 — 부르는 자리는 서비스가 변경을 적용한 직후다 */
    public static ContentPageHistoryEntity snapshotOf(
            ContentPageEntity page, MemberEntity changer, Instant changedAt) {
        return new ContentPageHistoryEntity(
                null,
                page,
                page.getTitle(),
                page.getBody(),
                page.getPublishStatus(),
                changer,
                changedAt);
    }
}
