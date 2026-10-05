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
 * oper_tag(운영_태그) — 운영 건을 묶어 거르는 꼬리표 (#637 · ssccops#576 · V31).
 *
 * 운영진이 업무명 앞에 «학술국»을 적어 구분하던 것이 이 태그가 됐다(#624 · ssccops#565). 주관 국도
 * 태그로 쓴다 — oper에 DEPT 역할 FK를 두는 길은 시드에 DEPT 역할이 0건이고 권한 체계와 엮여 범위가
 * 커져 택하지 않았다.
 *
 * **업무가 아니라 운영 건(oper)에 단다.** 업무·하위 업무·회의는 모두 oper의 확장이라, 태그를 oper에
 * 두면 셋이 같은 태그 목록을 쓰고 운영 통합이 행마다 같은 기준으로 칩·필터를 그린다(운영진 결정 ·
 * 2026-10-03). 처음(V29)에는 상위 업무에만 달았는데, 운영 통합에서 업무 행에만 칩이 있어 행마다
 * 기준이 갈렸다. 기각: 업무 태그는 그대로 두고 하위 업무·회의에 표를 따로 — 같은 태그가 세 벌이 된다.
 *
 * 모양은 폼 라벨(FormLabelEntity)을 본뜨되 **표는 공유하지 않는다** — 폼 라벨은 FORM_LABEL_MANAGE가
 * 관리하고 목록도 달라, 공유하면 폼 라벨 목록에 «학술국»이 섞인다.
 *
 * 폼 라벨과 갈리는 자리 둘(V29·V31 머리 주석과 같다):
 *  - use_yn이 없다. 지우면 oper_tag_rel도 함께 지워진다(운영 건은 그대로). 태그는 «지금 어느 국
 *    일인가»를 거르는 꼬리표라 과거 분류를 지킬 이유가 폼 라벨만큼 크지 않다.
 *  - 이름을 바꿀 수 있다(rename). 국 이름이 바뀌면 태그도 따라 바뀌어야 한다.
 *
 * tag_nm에 UNIQUE를 건다(uk_oper_tag_name). 이름은 사람이 태그를 고르는 유일한 단서라 같은 이름이
 * 둘이면 어느 쪽을 골랐는지 알 수 없다. 선조회만으로는 동시 생성을 막지 못해 제약을 DB에 둔다.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "oper_tag",
        uniqueConstraints = @UniqueConstraint(name = "uk_oper_tag_name", columnNames = "tag_nm"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class OperationTagEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "oper_tag_id")
    private Long id;

    @Column(name = "tag_nm", nullable = false, length = 50)
    private String name;

    @CreatedDate
    @Column(name = "crt_dt", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "mdfcn_dt", nullable = false)
    private Instant updatedAt;

    public static OperationTagEntity create(String name) {
        return new OperationTagEntity(null, name, null, null);
    }

    /*
     * 이름 변경. 이미 달린 운영 건의 칩도 함께 바뀐다 — 관계가 이름이 아니라 식별자를 들고 있어서다.
     * 그것이 이 동작의 목적이다(국 이름이 바뀌면 그 국의 운영 건 전부가 새 이름으로 보여야 한다).
     */
    public void rename(String name) {
        this.name = name;
    }
}
