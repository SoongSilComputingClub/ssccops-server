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
import jakarta.persistence.Table;

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
 * form_id는 **살아 있는 행사끼리** UNIQUE다(D11 · uk_event_form). 한 폼은 최대 한 행사에만
 * 전속되므로 응답이 어느 행사의 신청인지가 폼→행사 역참조 하나로 확정된다. 학술관리
 * (#131~#139)도 이 역참조를 딛고 선다. 신청이 생긴 뒤에도 연결은 바뀐다(#336).
 *
 * **그 UNIQUE는 이 클래스에 어노테이션으로 없다** (#347 · V8). 지운 행사가 폼을 붙잡지 않게
 * 하려고 제약을 PostgreSQL 부분 유니크 인덱스(WHERE del_dt IS NULL)로 바꿨는데, JPA는 그 조건을
 * 표현하지 못하고 H2(테스트)는 부분 인덱스 자체를 지원하지 않는다. @UniqueConstraint를 남기면
 * H2가 조건 없는 UNIQUE를 만들어 "지운 행사의 폼을 다른 행사가 쓴다"가 테스트에서 거절되고,
 * local의 ddl-auto: update는 V8이 지운 제약을 다시 세우려 든다. 그래서 H2에서의 규칙은 선조회
 * (EventRepository.existsByFormAndDeletedAtIsNull)이고 PostgreSQL에서는 인덱스가 동시 요청을 막는
 * 최종 방어선이다 — 초안 1건 규칙(FormResponseHistoryEntity · uk_form_rspns_hstry_one_draft)과
 * 같은 두 겹이다.
 *
 * 본문(mtxt_cn)은 md 원문만 저장·서빙한다(D12). 렌더링·sanitize는 공개 앱의 안전 렌더러
 * 책임이고, 길이 상한 검증은 저장 API가 건다 — DB는 TEXT라 제한하지 않는다.
 *
 * 상태 전이(게시·보관)는 EventStatusAction이 갖는다. 삭제는 소프트 삭제(del_dt · #347)이며
 * 참가자 수를 보지 않는다 — 막는 것은 학술 활동이 딸린 행사 하나뿐이고, 그 판정은 acdm_actv를
 * 봐야 해서 서비스(EventServiceImpl.deleteEvent)에 있다. 폼의 requireDeletable처럼 엔티티에
 * 두지 않은 것은 이 엔티티가 학술 연결을 조회할 수 없기 때문이다(폼 전속 선조회가 서비스에
 * 있는 것과 같은 이유).
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "event")
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
     * optional이다. 폼의 전속(살아 있는 행사 기준 1:0..1)은 부분 유니크 인덱스 uk_event_form이
     * DB에서 보장한다(D11 · V8) — 선조회만으로는 동시 연결을 못 막는다.
     *
     * **@OneToOne이 아니라 @ManyToOne인 것은 의도다** (#347). 지운 행사가 같은 폼을 계속 가리킬
     * 수 있으므로 행 수준에서는 폼 하나에 행사 여럿이 정상이고, 무엇보다 Hibernate가 @OneToOne
     * 조인 컬럼에는 조건 없는 UNIQUE를 스스로 만들어(H2 테스트 · local의 ddl-auto: update)
     * V8이 걷어낸 제약이 어노테이션 없이도 되살아난다. 도메인 규칙("살아 있는 행사끼리 전속")은
     * 매핑이 아니라 인덱스와 선조회가 말한다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
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

    /*
     * 소프트 삭제 시각 (#347 · ADR-0020). 살아 있는 행사는 NULL이다 — form.del_dt·oper.del_dt와
     * 같은 컬럼·같은 뜻이며 이름을 맞춘 것은 "삭제됐는가"를 묻는 자리가 도메인마다 다른 어휘를
     * 쓰지 않게 하려는 것이다.
     *
     * **지워진 행사는 목록·조회에서 없는 것과 같아진다.** 그 판정을 컬럼 하나로 두고 상태
     * (event_stts_cd)에 값을 더하지 않은 것이 요점이다 — DELETED를 전이표에 넣으면 게시 상태
     * (DRAFT·PUBLISHED·ARCHIVED)와 삭제 여부라는 서로 다른 두 축이 한 컬럼에서 겹쳐, 되살릴 때
     * "어느 상태로 돌아가는가"를 어디에도 적어 두지 않은 채 골라야 한다. 두 축을 나눠 두면
     * 되살리기는 이 값을 비우는 것뿐이고 게시 상태는 지울 때 그대로 남는다.
     *
     * **보관(ARCHIVED)과는 뜻이 다르다** (ADR-0020). 보관은 끝난 행사를 공개에서 내리되 운영
     * 기록으로 남기는 것이고 삭제는 잘못 만든 것을 목록에서 치우되 되돌릴 수 있게 두는 것이다.
     * ADR-0014가 걱정한 "숨기는 장치가 둘"은 뜻이 갈리면 문제가 아니다 — 폼도 CLOSED와 소프트
     * 삭제를 함께 갖는다.
     *
     * **참가자가 있어도 지운다** (ADR-0020 · 폼 #329와 같은 판단). 참가자 행은 남고, 그 사람의
     * '내 신청'에서 그 항목이 빠진다 — 되살리면 그대로 돌아온다. R2 이미지도 지우지 않는다.
     * 되살릴 수 있다는 것이 그 대가를 감당 가능하게 만드는 유일한 조건이다.
     *
     * 지워진 행사는 **폼을 붙잡지 않는다** — uk_event_form이 살아 있는 행사끼리만 걸리므로
     * (V8) 그 폼을 다른 행사가 쓸 수 있다. 그 뒤에 되살리면 폼이 겹치므로 복구가 409로 막힌다
     * (EventServiceImpl.restoreEvent).
     */
    @Column(name = "del_dt")
    private Instant deletedAt;

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
                // 새 행사는 언제나 살아 있다 — 지워진 채로 태어나는 경로를 두지 않는다 (#347)
                null,
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

    /*
     * 소프트 삭제 (#347). del_dt를 채우는 유일한 자리다.
     *
     * **참가자 수를 보지 않는다** (ADR-0020). 참가자가 있으면 못 지우게 하던 옛 규칙(D9 ·
     * EVENT_HAS_PARTICIPANT)은 ADR-0014에서 삭제와 함께 폐기됐고, 되살리지 않는다 — 실수로 만든
     * 행사에 신청이 하나만 들어와도 영영 목록에 남는 것이 폼 #329가 고친 증상과 같다.
     *
     * 게시 상태는 건드리지 않는다. 게시 중인 행사를 지우면 공개에서도 사라지지만 그것은
     * 조회가 del_dt를 보기 때문이지 상태가 바뀌어서가 아니다 — 되살리면 다시 게시 중이다.
     *
     * 이미 지워진 행사를 다시 지우는 것과 학술 활동이 딸린 행사는 여기서 막지 않고 서비스가
     * 409로 끊는다. 판정 자체는 isDeleted()로 이 클래스가 갖되, 무엇으로 거절할지는 삭제·복구
     * 두 경로의 대칭을 아는 쪽이 정하는 것이 맞다 (FormEntity.softDelete와 같은 판단).
     *
     * 시각을 인자로 받는 것은 Instant.now()를 직접 부르면 테스트에서 고정할 수 없기 때문이다
     * (FormEntity.softDelete 선례 · ClockConfig).
     */
    public void softDelete(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    /*
     * 되살리기 (#347). del_dt를 비우는 유일한 자리다.
     *
     * **게시 상태·폼 연결·일시·본문을 건드리지 않는다.** 되살린 행사는 지우기 직전 그대로이며,
     * 게시 중이던 행사를 지웠다 되살리면 다시 게시 중이다 (ADR-0020 규칙). DRAFT로 되돌리는
     * 안은 되살리기가 "복구"가 아니라 "상태를 하나 더 바꾸는 조작"이 되어 기각했다 —
     * FormEntity.restore와 같은 판단이다.
     *
     * 폼 연결도 그대로다. 그 폼을 그새 다른 행사가 가져갔으면 되살릴 수 없고(409), 연결을 풀고
     * 되살리는 안은 택하지 않았다 — 근거는 EventServiceImpl.restoreEvent에 있다.
     *
     * 참가자 명단·R2 이미지는 애초에 지우지 않았으므로 되살릴 것도 없다.
     */
    public void restore() {
        this.deletedAt = null;
    }

    /** 지워진 행사인가 (#347). 살아 있으면 del_dt가 NULL이다 */
    public boolean isDeleted() {
        return this.deletedAt != null;
    }
}
