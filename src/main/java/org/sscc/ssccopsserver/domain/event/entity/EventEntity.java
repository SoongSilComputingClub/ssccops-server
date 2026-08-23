package org.sscc.ssccopsserver.domain.event.entity;

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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.sscc.ssccopsserver.domain.event.code.EventStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * event(행사) — 모집공고·세미나·프로젝트 참가·홈커밍 등 모든 행사를 하나로 다루는 md 게시물
 * (ssccops#133 · wave2). 물리명은 데이터사전+테이블컬럼정의서(2026-08-22 반영)가 기준이다.
 *
 * 행사는 폼을 참조만 한다(D3). 모집 기간·접수 가능 판정·마감은 전부 연결된 폼의
 * FormReceiptPolicy 소관이고 행사에는 행사 일시만 둔다 — 같은 사실을 두 곳에 적지 않으므로
 * "행사는 모집 중인데 폼은 마감"이 구조적으로 불가능하다.
 *
 * form_id는 UNIQUE다(D11). 한 폼은 최대 한 행사에만 전속되므로 응답이 어느 행사의 신청인지가
 * 폼→행사 역참조 하나로 확정된다. 학술관리(#131~#139)도 이 역참조를 딛고 선다. 신청이 생긴
 * 뒤의 연결 변경·해제 409 거절은 행사 CRUD(ssccops#139·#142)의 몫이다.
 *
 * 본문(mtxt_cn)은 md 원문만 저장·서빙한다(D12). 렌더링·sanitize는 공개 앱의 안전 렌더러
 * 책임이고, 길이 상한 검증은 저장 API가 건다 — DB는 TEXT라 제한하지 않는다.
 *
 * 상태 전이(게시·보관)와 삭제 가드(참가자 있으면 409)는 셋업 범위 밖이다 — 전이표는
 * 행사 CRUD(ssccops#139)가 폼의 FormStatusAction 패턴으로 확정한다.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "event",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_event_form",
                        columnNames = {"form_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class EventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_id")
    private Long id;

    /** 행사 분류. 행사당 정확히 1개다 (D13) */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_clsf_cd", nullable = false)
    private EventClassificationEntity classification;

    @Column(name = "event_ttl", nullable = false, length = 256)
    private String title;

    /** md 원문. 원시 HTML은 허용하지 않는다 (D12) */
    @Column(name = "mtxt_cn", nullable = false, columnDefinition = "TEXT")
    private String contentMarkdown;

    /** 대표 이미지 공개 URL. OG og:image에 쓰인다 (D6·D7). 없으면 NULL */
    @Column(name = "thmb_url_addr", length = 200)
    private String thumbnailUrlAddress;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_stts_cd", nullable = false, length = 20)
    private EventStatus status;

    /*
     * 연결 폼(form.form_id). NULL이면 폼 없는 공지다 — 신청 없이 게시만 하는 행사가 정상이라
     * optional이다. UNIQUE(uk_event_form)는 폼의 전속(1:0..1)을 DB가 보장하는 자리다(D11) —
     * 선조회만으로는 동시 연결을 못 막는다.
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "form_id")
    private FormEntity form;

    /*
     * 행사 일시. 둘 다 NULL을 허용한다 — 일시가 정해지지 않은 공지가 정상이다.
     * 예정/진행중/종료(eventPhase)는 이 값에서 조회 시점에 파생하고 저장하지 않는다(D9).
     */
    @Column(name = "event_bgng_dt")
    private Instant beginAt;

    @Column(name = "event_end_dt")
    private Instant endAt;

    @Column(name = "plc_nm", length = 100)
    private String placeName;

    /** 정원. NULL이면 무제한. 참고치라 확정 등록이 이 값을 강제하지 않는다 (D5) */
    @Column(name = "ptcp_lmt_cnt")
    private Integer participantLimitCount;

    /** 생성자(mbr.mbr_id). 인증 주체를 서버가 기록하며 사후 변경 불가다 — 폼과 같은 태도 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creatr_mbr_id", nullable = false, updatable = false)
    private MemberEntity creator;

    @CreatedDate
    @Column(name = "crt_dt", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "mdfcn_dt", nullable = false)
    private Instant updatedAt;

    /*
     * 행사 생성 팩토리. 상태는 항상 DRAFT다 — 만들자마자 공개되는 경로를 두지 않는다.
     * 데이터사전의 event_stts_cd 기본값 DRAFT를 DB가 아니라 여기서 확정하는 것도 폼과 같다.
     */
    public static EventEntity create(
            EventClassificationEntity classification,
            MemberEntity creator,
            String title,
            String contentMarkdown,
            String thumbnailUrlAddress,
            FormEntity form,
            Instant beginAt,
            Instant endAt,
            String placeName,
            Integer participantLimitCount) {
        return new EventEntity(
                null,
                classification,
                title,
                contentMarkdown,
                thumbnailUrlAddress,
                EventStatus.DRAFT,
                form,
                beginAt,
                endAt,
                placeName,
                participantLimitCount,
                creator,
                null,
                null);
    }
}
