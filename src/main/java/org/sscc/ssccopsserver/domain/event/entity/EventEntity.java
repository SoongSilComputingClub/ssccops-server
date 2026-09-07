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
import org.sscc.ssccopsserver.domain.event.code.EventStatusAction;
import org.sscc.ssccopsserver.domain.event.code.error.EventErrorCode;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

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

    /*
     * 행사 수정 (ssccops#139 · PUT). 상태(event_stts_cd)를 받지 않는다 — 폼 PUT과 같은 태도로,
     * 상태를 바꾸는 길은 changeStatus 하나로 좁힌다. 생성자(creator)도 바꾸지 않는다
     * (updatable = false).
     *
     * 폼 연결(form)을 여기서 함께 받는 것은 편집 화면이 본문·일시·폼 연결을 한 저장으로 다루기
     * 때문이다. 신청 발생 후 연결 변경·해제 금지(D11)는 저장 전 검증이 필요해 서비스가 막는다 —
     * 엔티티는 현재 폼의 응답 존재 여부를 조회할 수 없다.
     */
    public void update(
            EventClassificationEntity classification,
            String title,
            String contentMarkdown,
            String thumbnailUrlAddress,
            FormEntity form,
            Instant beginAt,
            Instant endAt,
            String placeName,
            Integer participantLimitCount) {
        this.classification = classification;
        this.title = title;
        this.contentMarkdown = contentMarkdown;
        this.thumbnailUrlAddress = thumbnailUrlAddress;
        this.form = form;
        this.beginAt = beginAt;
        this.endAt = endAt;
        this.placeName = placeName;
        this.participantLimitCount = participantLimitCount;
    }

    /*
     * 게시 상태 전이 (ssccops#139 · POST /v1/events/{eventId}/status).
     *
     * 전이표는 EventStatusAction이 갖고, 여기서는 표를 어긴 요청을 무엇으로 거절할지만 맡는다
     * (FormEntity.changeStatus 선례). 폼과 달리 여는 쪽 사전 검증이 없다 — 본문이 비어 있어도
     * 게시는 운영자의 판단이고, 폼 연결·폼 상태와 게시가 독립이라는 것이 D11의 결정이다.
     *
     * 전이 이력은 남기지 않는다. 데이터사전에 행사 상태 이력 테이블이 없다 (폼과 같은 태도).
     */
    public void changeStatus(EventStatusAction action) {
        if (!action.isAllowedFrom(this.status)) {
            throw new GeneralException(EventErrorCode.INVALID_EVENT_STATUS_TRANSITION);
        }
        this.status = action.targetStatus();
    }

    /*
     * 본문·대표 이미지의 주소만 바꿔 쓴다 (ssccops#198 · 행사 복제 결정 2).
     *
     * 사본은 식별자를 받은 뒤에야 자기 오브젝트 키를 알 수 있어, 원본 주소로 먼저 저장하고 나서
     * 그 주소를 사본의 것으로 옮겨 적는다. update()로도 되지만 그쪽은 아홉 값을 전부 다시 받으므로
     * "주소 말고는 아무것도 바뀌지 않는다"는 사실이 호출부에서 보이지 않는다.
     */
    public void relocateImages(String contentMarkdown, String thumbnailUrlAddress) {
        this.contentMarkdown = contentMarkdown;
        this.thumbnailUrlAddress = thumbnailUrlAddress;
    }

    /*
     * 연결 폼 지정 (#133 학술 활동 승인 후속 처리 전용). 학술 활동은 승인(=생성) 시점에
     * Event만 먼저 만들고, 같은 트랜잭션에서 모집용 빈 폼을 만든 뒤 이 메서드로 연결한다 —
     * create() 팩토리 하나로 두 순서를 다 감당하려 하면 "폼을 만들기 전에는 Event를 못 만든다"
     * 는 제약이 생겨 승인 후속 처리 트랜잭션이 더 복잡해진다. uk_event_form(form.form_id
     * UNIQUE)이 한 폼이 두 행사에 전속되는 것을 DB 레벨에서 막는다.
     */
    public void linkForm(FormEntity form) {
        this.form = form;
    }
}
