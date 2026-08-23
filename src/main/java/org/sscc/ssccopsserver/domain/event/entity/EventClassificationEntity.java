package org.sscc.ssccopsserver.domain.event.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * event_clsf(행사_분류) — 행사 분류 코드테이블 (ssccops#133 · D13).
 *
 * role_clsf처럼 화면에서 추가·수정하는 운영 데이터 코드테이블이라 서버 enum으로 굳히지 않는다 —
 * data.sql이 넣는 모집/세미나/프로젝트/행사는 고정 어휘가 아니라 초기값이다. 관리 API는
 * 행사 분류 관리(ssccops#140)의 몫이다.
 *
 * 기획서(wave2) 초안의 event_ctgr에 해당한다. 데이터사전 표준단어가 분류를 clsf로 정하고
 * role_clsf 선례가 있어 event_clsf로 표준화됐다 — 물리명은 데이터사전이 기준이다.
 */
@Entity
@Table(name = "event_clsf")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class EventClassificationEntity {

    @Id
    @Column(name = "event_clsf_cd", length = 20)
    private String code;

    @Column(name = "event_clsf_nm", nullable = false, length = 50)
    private String name;

    @Column(name = "indct_seqno", nullable = false)
    private Integer displayOrder;

    public static EventClassificationEntity create(String code, String name, Integer displayOrder) {
        return new EventClassificationEntity(code, name, displayOrder);
    }

    public void update(String name, Integer displayOrder) {
        this.name = name;
        this.displayOrder = displayOrder;
    }
}
