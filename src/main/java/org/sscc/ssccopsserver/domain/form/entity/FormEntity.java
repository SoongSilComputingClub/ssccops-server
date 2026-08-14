package org.sscc.ssccopsserver.domain.form.entity;

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

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * form(폼) — 지원서·신청서의 마스터. 한 행이 화면 하나가 아니라 폼 하나 전체를 담는다.
 *
 * 문항은 별도 테이블이 아니라 qitem_cpst_cn(JSONB) 한 컬럼에 들어 있다. 폼마다 문항의
 * 개수·유형·검증 규칙이 전부 다르고, 폼을 한 번 그리려면 어차피 문항 전부가 필요해서
 * 정규화해도 매번 전량 조회가 된다 (자세한 근거는 QuestionCompositionContent 주석).
 *
 * 라벨은 여기에 컬럼으로 두지 않고 form_lbl_rel로 N:M 연결한다. 한 폼이 '신규모집'과
 * '2026'을 동시에 달 수 있어야 하고, 라벨 자체는 화면에서 늘어나는 운영 데이터라서다.
 * 컬렉션 연관(@OneToMany)을 열지 않은 것은 폼 목록이 라벨을 폼마다 한 번씩 조회해
 * N+1이 되는 것을 막기 위해서다 — 라벨은 FormLabelRelationRepository로 한 번에 모아 온다.
 *
 * 상태 전이(DRAFT → OPEN → CLOSED)와 수정 규칙은 폼 CRUD가 붙는 #32의 범위라 여기에는
 * 생성 팩토리만 둔다. 상태를 직접 쓰는 setter를 열지 않는 것이 그 통제의 전제다.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "form")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class FormEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "form_id")
    private Long id;

    /*
     * 생성자(mbr.mbr_id). 인증 주체를 서버가 기록하며 클라이언트가 지정할 수 없다.
     * 사후 변경 불가라 updatable = false로 잠근다 — 폼을 넘겨받는 개념이 없다.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creatr_mbr_id", nullable = false, updatable = false)
    private MemberEntity creator;

    @Column(name = "form_ttl_nm", nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "form_stts_cd", nullable = false, length = 20)
    private FormStatus status;

    /*
     * 접수 기간. 둘 다 NULL을 허용한다 — 기간 제한 없이 열어 두는 폼이 있고,
     * 작성 중(DRAFT)에는 아직 기간을 정하지 않은 상태가 정상이기 때문이다.
     * 상태(OPEN)와 기간은 별개 축이라 실제 응답 가능 여부는 두 값을 함께 봐야 한다 (#35).
     */
    @Column(name = "rcpt_bgng_dt")
    private Instant receiptBeginAt;

    @Column(name = "rcpt_end_dt")
    private Instant receiptEndAt;

    /*
     * 문항 구성(JSONB). @JdbcTypeCode(SqlTypes.JSON)만으로 Hibernate 6가 방언별 타입을
     * 골라 준다 — PostgreSQL은 jsonb, H2는 JSON이다. columnDefinition에 'jsonb'를 박으면
     * 테스트가 도는 H2에서 DDL이 깨지므로 방언 판단을 가로채지 않는다.
     *
     * 직렬화는 Hibernate가 Jackson으로 처리한다(클래스패스에 있으면 자동 선택). 역직렬화가
     * 깨졌을 때 500이 아니라 도메인 오류로 내리는 변환은 JsonFormatMapperConfig가 맡는다.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "qitem_cpst_cn", nullable = false)
    private QuestionCompositionContent questionComposition;

    @CreatedDate
    @Column(name = "crt_dt", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "mdfcn_dt", nullable = false)
    private Instant updatedAt;

    /*
     * 폼 생성 팩토리. 상태는 항상 DRAFT다 — 만들자마자 공개되는 경로를 두지 않는다.
     * 데이터사전의 form_stts_cd 기본값 DRAFT를 DB가 아니라 여기서 확정하는 것은,
     * 기본값을 DB에만 두면 엔티티를 읽기 전까지 상태가 NULL로 보이기 때문이다.
     */
    public static FormEntity create(
            MemberEntity creator,
            String title,
            QuestionCompositionContent questionComposition,
            Instant receiptBeginAt,
            Instant receiptEndAt) {
        return create(
                creator,
                title,
                questionComposition,
                receiptBeginAt,
                receiptEndAt,
                FormStatus.DRAFT);
    }

    /*
     * 상태를 지정해 만드는 생성 팩토리 (#32). 폼 편집 화면의 '바로 접수 시작'이 만들자마자
     * OPEN인 폼을 요구해서 열어 둔다 — 만들고 나서 상태를 한 번 더 바꾸게 하면 두 번째 호출이
     * 실패했을 때 사용자가 의도하지 않은 DRAFT 폼이 남는다.
     *
     * 상태를 자유롭게 넣을 수 있게 된 만큼 어떤 상태가 허용되는지는 호출부(서비스)가 정한다.
     * 상태 전이 규칙 자체는 접수 상태 전이 API(#33)의 범위다.
     */
    public static FormEntity create(
            MemberEntity creator,
            String title,
            QuestionCompositionContent questionComposition,
            Instant receiptBeginAt,
            Instant receiptEndAt,
            FormStatus status) {
        return new FormEntity(
                null,
                creator,
                title,
                status,
                receiptBeginAt,
                receiptEndAt,
                questionComposition,
                null,
                null);
    }

    /*
     * 폼 수정 (#32 · PUT). 문항 구성은 부분 갱신이 아니라 통째로 교체한다 —
     * QuestionCompositionContent가 모르는 필드를 보존하지 못하므로(ignoreUnknown) 병합하면
     * 클라이언트가 보내지 않은 항목이 조용히 사라진 채 반쯤 남는다.
     *
     * 생성자(creator)는 바꾸지 않는다. 폼을 넘겨받는 개념이 없어 컬럼 자체가 updatable = false다.
     */
    public void update(
            String title,
            FormStatus status,
            QuestionCompositionContent questionComposition,
            Instant receiptBeginAt,
            Instant receiptEndAt) {
        this.title = title;
        this.status = status;
        this.questionComposition = questionComposition;
        this.receiptBeginAt = receiptBeginAt;
        this.receiptEndAt = receiptEndAt;
    }
}
