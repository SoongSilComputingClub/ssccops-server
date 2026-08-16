package org.sscc.ssccopsserver.domain.form.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * form_lbl_rel(폼_라벨_관계) — 폼과 라벨의 N:M 연결.
 *
 * @ManyToMany + @JoinTable로 감추지 않고 엔티티로 세운 것은 이 행이 crt_dt(연결 시점)를
 * 갖는 데이터이기 때문이다. 연결 시점을 가지면 그 순간부터 관계 테이블이 아니라 기록이 된다.
 * 목록 조회가 폼별 라벨을 한 번에 모아 오려면 이 테이블을 직접 질의해야 하기도 하다.
 *
 * (form_id, form_lbl_id) UNIQUE를 건다. 같은 라벨을 같은 폼에 두 번 달면 폼 상세에 라벨이
 * 두 번 뜨고, 라벨별 폼 수 집계가 부풀며, 떼어낼 때 한 행만 지워져 하나가 남는다.
 * 선조회만으로는 동시 요청을 막지 못하므로 제약을 DB에 둔다 — 회원 학번(#21)과 같은 이유다.
 *
 * mdfcn_dt가 없는 것은 데이터사전 그대로다. 이 행에는 바꿀 값이 없어서 붙였다 떼는 것만
 * 있고, 그래서 @LastModifiedDate도 두지 않는다.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "form_lbl_rel",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_form_lbl_rel_form_label",
                        columnNames = {"form_id", "form_lbl_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class FormLabelRelationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "form_lbl_rel_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "form_id", nullable = false, updatable = false)
    private FormEntity form;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "form_lbl_id", nullable = false, updatable = false)
    private FormLabelEntity label;

    @CreatedDate
    @Column(name = "crt_dt", nullable = false, updatable = false)
    private Instant createdAt;

    /*
     * 연결 생성. 라벨이 비활성인지(use_yn = false) 여기서 보지 않는다 — 새로 달 수 없는
     * 라벨인지는 라벨 관리 정책(#34)이 판단할 일이고, 엔티티가 다른 엔티티의 상태로
     * 자기 생성을 막기 시작하면 이관·복구 같은 정당한 경로까지 함께 막힌다.
     */
    public static FormLabelRelationEntity create(FormEntity form, FormLabelEntity label) {
        return new FormLabelRelationEntity(null, form, label, null);
    }
}
