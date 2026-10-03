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
 * work_tag_rel(업무_태그_관계) — 업무와 태그의 N:M 연결 (#624 · V29).
 *
 * FormLabelRelationEntity와 같은 판단이다. @ManyToMany로 감추지 않고 엔티티로 세운 것은 이 행이
 * crt_dt(지정 시각)를 갖는 기록이기 때문이고, 목록이 업무별 태그를 한 번에 모아 오려면 이 표를
 * 직접 질의해야 하기도 하다. 바꿀 값이 없어 붙였다 떼는 것만 있으므로 mdfcn_dt가 없다.
 *
 * (work_id, work_tag_id) UNIQUE를 건다 — 같은 태그를 두 번 달면 칩이 두 번 뜨고 사용 건수가 부푼다.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "work_tag_rel",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_work_tag_rel_work_tag",
                        columnNames = {"work_id", "work_tag_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class WorkTagRelationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "work_tag_rel_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "work_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_work_tag_rel_work"))
    private WorkEntity work;

    /*
     * 태그를 지우면 지정도 함께 지워진다(V29 ON DELETE CASCADE). 제약 이름을 V29와 같게 박는 것은
     * H2(테스트 · ddl-auto: create)가 해시 이름을 만들지 않게 하려는 것이다 —
     * EventParticipantStatusHistoryEntity와 같다.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(
            name = "work_tag_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_work_tag_rel_work_tag"))
    private WorkTagEntity tag;

    @CreatedDate
    @Column(name = "crt_dt", nullable = false, updatable = false)
    private Instant createdAt;

    public static WorkTagRelationEntity create(WorkEntity work, WorkTagEntity tag) {
        return new WorkTagRelationEntity(null, work, tag, null);
    }
}
