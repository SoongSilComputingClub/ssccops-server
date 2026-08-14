package org.sscc.ssccopsserver.domain.form.entity;

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
 * form_lbl(폼_라벨) — 폼을 묶어 거르기 위한 꼬리표(신규모집·회원연장·행사·스터디·2026·1학기 등).
 *
 * 등급·상태와 달리 enum으로 굳히지 않는다. 화면(#34)에서 추가·수정하는 사용자 관리
 * 데이터이고, 연도·학기처럼 해마다 늘어나는 값이 섞여 있어 고정 어휘가 아니기 때문이다.
 * role·role_clsf와 같은 성격이다.
 *
 * 지우는 대신 use_yn을 내린다. 이미 폼에 달린 라벨을 삭제하면 그 폼의 분류 이력이 사라지고,
 * form_lbl_rel의 FK를 함께 지워야 해서 과거 폼 목록의 필터 결과가 조용히 바뀐다.
 * 비활성 라벨은 "새로 달 수 없고 필터 목록에 뜨지 않을 뿐" 이미 달린 것은 그대로 남는다.
 *
 * lbl_nm에 UNIQUE를 건다(#34에서 추가). 데이터사전에는 없지만 라벨 관리 화면이 이름 중복을
 * 막는 것을 전제로 만들어져 있고, 이름은 사람이 라벨을 고르는 유일한 단서라 같은 이름이 둘이면
 * 어느 쪽을 골랐는지 알 수 없다. 선조회만으로는 동시 생성을 막지 못하므로 제약을 DB에 둔다 —
 * 회원 학번(#21)과 같은 이유다.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "form_lbl",
        uniqueConstraints = @UniqueConstraint(name = "uk_form_lbl_name", columnNames = "lbl_nm"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class FormLabelEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "form_lbl_id")
    private Long id;

    @Column(name = "lbl_nm", nullable = false, length = 50)
    private String name;

    /*
     * 사용 여부. 이름이 usable이 아니라 active인 것은 화면이 이 값을 "활성/비활성"으로
     * 보여주기 때문이다. 컬럼명(use_yn)은 데이터사전 표기를 그대로 따른다.
     */
    @Column(name = "use_yn", nullable = false)
    private boolean active;

    @CreatedDate
    @Column(name = "crt_dt", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "mdfcn_dt", nullable = false)
    private Instant updatedAt;

    /** 라벨 생성. 새로 만든 라벨은 항상 활성이다 — 만들자마자 비활성일 이유가 없다 */
    public static FormLabelEntity create(String name) {
        return new FormLabelEntity(null, name, true, null, null);
    }

    public void rename(String name) {
        this.name = name;
    }

    /*
     * 활성/비활성 전환. 켜기·끄기 전용 메서드가 아니라 값을 받는 것은 화면의 토글이
     * 같은 자리에서 양쪽으로 움직이기 때문이다. 같은 값을 다시 넣어도 결과가 같다.
     */
    public void changeActive(boolean active) {
        this.active = active;
    }
}
