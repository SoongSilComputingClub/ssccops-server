package org.sscc.ssccopsserver.domain.operation.entity;

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
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * oper(운영) — 업무(work)와 회의(mtg)가 공유하는 공통 속성을 담는 부모 테이블.
 * 확장 테이블이 oper_id를 FK로 참조하며, PK=FK 상속이 아니므로 @MapsId를 쓰지 않는다.
 *
 * 컬럼은 데이터사전(테이블ID: oper)을 따른다. Seq 6·7·9·10·11(종일여부·부서·공개범위·
 * 기수)은 데이터사전 개정으로 삭제된 결번이라 매핑하지 않는다.
 * bgng_dt·end_dt·del_dt만 NULL을 허용하고 나머지는 NOT NULL이다.
 *
 * prrty_rnk_cd(우선순위)는 등록 화면에 입력란이 있어 되살린 컬럼이다.
 */
/*
 * 인덱스 둘은 상위 업무 목록(OPS-020)의 정렬 키다 (DB-17·AGG-06). 정렬 대상이 work가 아니라
 * 여기인 것은 제목·기간·등록시각 같은 공통 속성을 부모가 갖기 때문이며, 커서 페이징은 정렬
 * 키를 매 요청 훑으므로 인덱스가 없으면 그대로 전체 스캔이 된다.
 *
 * 주의: prod도 ddl-auto가 update이므로(정식 버전 전까지 한시적) 이 선언은 배포 때 반영된다.
 * update는 추가만 하고 삭제·이름 변경·타입 변경은 반영하지 않으니, 아래 DDL은 그런 변경이
 * 필요할 때와 정식 버전에서 ddl-auto를 none으로 되돌린 뒤를 위한 기준으로 남긴다.
 *   CREATE INDEX idx_oper_crt_dt ON oper (crt_dt);
 *   CREATE INDEX idx_oper_bgng_dt ON oper (bgng_dt);
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "oper",
        indexes = {
            @Index(name = "idx_oper_crt_dt", columnList = "crt_dt"),
            @Index(name = "idx_oper_bgng_dt", columnList = "bgng_dt")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class OperationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "oper_id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "oper_type_cd", nullable = false, length = 20)
    private OperationType operationType;

    @Column(name = "oper_ttl", nullable = false, length = 256)
    private String title;

    /*
     * 등록자(mbr.mbr_id). 인증 주체를 서버가 기록하며 클라이언트가 지정할 수 없다 (LY-05).
     * 담당자(pic_id)와는 다른 축이다 — 남을 담당자로 지정해 등록할 수 있다.
     *
     * 사후 변경 불가라 updatable = false로 잠근다. 데이터사전은 NULL을 허용하지만(등록자를
     * 알 수 없는 이관 데이터 대비) 신규 등록 건은 항상 채운다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "oper_rgtr_id", updatable = false)
    private MemberEntity registrant;

    @Column(name = "bgng_dt")
    private Instant beginAt;

    @Column(name = "end_dt")
    private Instant endAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "prrty_rnk_cd", nullable = false, length = 20)
    private OperationPriority priority;

    /*
     * 담당자(mbr.mbr_id). 조회·매핑 전용 연관이며 회원의 상태를 여기서 바꾸지 않는다 —
     * 회원 정보 변경이 필요하면 회원 도메인 Service를 호출한다 (개발지침서 DB-10·AR-07).
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pic_id", nullable = false)
    private MemberEntity personInCharge;

    // 소프트 삭제 시각. 살아있는 운영 건은 NULL (LY-18)
    @Column(name = "del_dt")
    private Instant deletedAt;

    @CreatedDate
    @Column(name = "crt_dt", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "mdfcn_dt", nullable = false)
    private Instant updatedAt;

    /*
     * 업무 등록(OPS-002)용 생성 팩토리. oper_type_cd = WORK는 서버가 고정하며
     * 클라이언트가 지정할 수 없다.
     */
    public static OperationEntity createForWork(
            String title,
            MemberEntity registrant,
            MemberEntity personInCharge,
            Instant beginAt,
            Instant endAt,
            OperationPriority priority) {
        return new OperationEntity(
                null,
                OperationType.WORK,
                title,
                registrant,
                beginAt,
                endAt,
                priority == null ? OperationPriority.NORMAL : priority,
                personInCharge,
                null,
                null,
                null);
    }

    /*
     * 하위 업무 등록(OPS-007)용 생성 팩토리. 하위 업무도 제목·기간·담당자·우선순위를
     * oper에 두므로 자기 운영 건을 하나 갖는다. 상위 업무의 oper를 재사용하지 않는다.
     */
    public static OperationEntity createForSubWork(
            String title,
            MemberEntity registrant,
            MemberEntity personInCharge,
            Instant beginAt,
            Instant endAt,
            OperationPriority priority) {
        return new OperationEntity(
                null,
                OperationType.SUB_WORK,
                title,
                registrant,
                beginAt,
                endAt,
                priority == null ? OperationPriority.NORMAL : priority,
                personInCharge,
                null,
                null,
                null);
    }

    /*
     * 회의 등록(OPS-024)용 생성 팩토리. oper_type_cd = MEETING은 서버가 고정하며
     * 클라이언트가 지정할 수 없다. 담당자(personInCharge)는 회의 책임자와 항상 동일 인물이라
     * mtg_rbprsn_id에도 같은 회원을 넣는다 — 별도로 입력받지 않는다(ssccops-web#56).
     */
    public static OperationEntity createForMeeting(
            String title,
            MemberEntity registrant,
            MemberEntity personInCharge,
            Instant beginAt,
            Instant endAt,
            OperationPriority priority) {
        return new OperationEntity(
                null,
                OperationType.MEETING,
                title,
                registrant,
                beginAt,
                endAt,
                priority == null ? OperationPriority.NORMAL : priority,
                personInCharge,
                null,
                null,
                null);
    }

    public void changePriority(OperationPriority priority) {
        this.priority = priority;
    }

    public void changeTitle(String title) {
        this.title = title;
    }

    public void changeSchedule(Instant beginAt, Instant endAt) {
        this.beginAt = beginAt;
        this.endAt = endAt;
    }

    public void changePersonInCharge(MemberEntity personInCharge) {
        this.personInCharge = personInCharge;
    }

    public void softDelete(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    public boolean isDeleted() {
        return this.deletedAt != null;
    }
}
