package org.sscc.ssccopsserver.domain.operation.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * oper_tag_rel(운영_태그_관계) — 운영 건과 태그의 N:M 연결 (#637 · V31).
 *
 * 운영 건(oper)을 가리킨다 — 업무·하위 업무·회의가 각자의 확장 행이 아니라 공통 oper_id로 같은
 * 태그를 단다(OperationTagEntity 주석). FormLabelRelationEntity와 같은 판단으로 @ManyToMany로 감추지
 * 않고 엔티티로 세웠다 — 이 행이 crt_dt(지정 시각)를 갖는 기록이고, 목록이 운영 건별 태그를 한 번에
 * 모아 오려면 이 표를 직접 질의해야 한다. 바꿀 값이 없어 붙였다 떼는 것만 있으므로 mdfcn_dt가 없다.
 *
 * (oper_id, oper_tag_id) UNIQUE를 건다 — 같은 태그를 두 번 달면 칩이 두 번 뜨고 사용 건수가 부푼다.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "oper_tag_rel",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_oper_tag_rel_oper_tag",
                        columnNames = {"oper_id", "oper_tag_id"}),
        indexes = @Index(name = "idx_oper_tag_rel_oper_tag_id", columnList = "oper_tag_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class OperationTagRelationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "oper_tag_rel_id")
    private Long id;

    /*
     * 운영 건은 소프트 삭제(oper.del_dt)라 oper 행이 지워지는 일이 없어 V31도 NO ACTION이다. 지운 운영
     * 건의 관계는 남지만 목록·건수가 del_dt로 거른다(OperationTagRelationRepository 주석).
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "oper_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_oper_tag_rel_oper"))
    private OperationEntity operation;

    /*
     * 태그를 지우면 지정도 함께 지워진다(V31 ON DELETE CASCADE). 제약 이름을 V31과 같게 박는 것은
     * H2(테스트 · ddl-auto: create)가 해시 이름을 만들지 않게 하려는 것이다 —
     * EventParticipantStatusHistoryEntity와 같다.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(
            name = "oper_tag_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_oper_tag_rel_oper_tag"))
    private OperationTagEntity tag;

    @CreatedDate
    @Column(name = "crt_dt", nullable = false, updatable = false)
    private Instant createdAt;

    public static OperationTagRelationEntity create(
            OperationEntity operation, OperationTagEntity tag) {
        return new OperationTagRelationEntity(null, operation, tag, null);
    }
}
