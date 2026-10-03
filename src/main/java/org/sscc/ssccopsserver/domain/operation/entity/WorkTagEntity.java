package org.sscc.ssccopsserver.domain.operation.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * work_tag(업무_태그) — 업무를 묶어 거르는 꼬리표 (#624 · ssccops#565 · V29).
 *
 * 운영진이 업무명 앞에 «학술국»을 적어 구분하던 것이 이 태그가 됐다. 주관 국도 태그로 쓴다 —
 * work에 DEPT 역할 FK를 두는 길은 시드에 DEPT 역할이 0건이고 권한 체계와 엮여 범위가 커져 택하지
 * 않았다. 모양은 폼 라벨(FormLabelEntity)을 본뜨되 **표는 공유하지 않는다** — 폼 라벨은
 * FORM_LABEL_MANAGE가 관리하고 목록도 달라, 공유하면 폼 라벨 목록에 «학술국»이 섞인다.
 *
 * 폼 라벨과 갈리는 자리 둘(V29 머리 주석과 같다):
 *  - use_yn이 없다. 지우면 work_tag_rel도 함께 지워진다(업무는 그대로). 업무 태그는 «지금 어느
 *    국 일인가»를 거르는 꼬리표라 과거 분류를 지킬 이유가 폼 라벨만큼 크지 않다.
 *  - 이름을 바꿀 수 있다(rename). 국 이름이 바뀌면 태그도 따라 바뀌어야 한다.
 *
 * tag_nm에 UNIQUE를 건다(uk_work_tag_name). 이름은 사람이 태그를 고르는 유일한 단서라 같은 이름이
 * 둘이면 어느 쪽을 골랐는지 알 수 없다. 선조회만으로는 동시 생성을 막지 못해 제약을 DB에 둔다.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "work_tag",
        uniqueConstraints = @UniqueConstraint(name = "uk_work_tag_name", columnNames = "tag_nm"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class WorkTagEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "work_tag_id")
    private Long id;

    @Column(name = "tag_nm", nullable = false, length = 50)
    private String name;

    @CreatedDate
    @Column(name = "crt_dt", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "mdfcn_dt", nullable = false)
    private Instant updatedAt;

    public static WorkTagEntity create(String name) {
        return new WorkTagEntity(null, name, null, null);
    }

    /*
     * 이름 변경. 이미 달린 업무의 칩도 함께 바뀐다 — 관계가 이름이 아니라 식별자를 들고 있어서다.
     * 그것이 이 동작의 목적이다(국 이름이 바뀌면 그 국의 업무 전부가 새 이름으로 보여야 한다).
     */
    public void rename(String name) {
        this.name = name;
    }
}
