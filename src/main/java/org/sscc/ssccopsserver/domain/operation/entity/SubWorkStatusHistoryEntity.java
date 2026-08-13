package org.sscc.ssccopsserver.domain.operation.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * sub_work_stts_hstry(하위 업무 상태 이력) — 전이가 일어날 때마다 한 건씩 쌓이는 불변 이력.
 *
 * 전/후 상태 컬럼(bfr_work_stts_cd·aftr_work_stts_cd)은 이 작업에서 데이터사전에 신설했다.
 * 그 전에는 '무엇이 무엇으로 바뀌었는지'가 남지 않아 OPS-011(상태 전환 이력 조회)을
 * 구현할 수 없었다. 표기는 회원 도메인의 mbr_grd_hstry·mbr_stts_hstry를 따랐다.
 *
 * 변경 메서드를 열지 않는다 — 이력은 수정·삭제하지 않는다 (POL-004·BR-O11·AP-09·LG-13).
 *
 * chg_dt는 DB 기본값이 아니라 애플리케이션이 Clock으로 채운다. 한 번의 전이가 이력과
 * 승인/반려 레코드를 함께 만드는데, 한쪽만 DB 시각을 쓰면 같은 전이의 시각이 어긋나고
 * 테스트에서 시각을 고정할 수도 없다.
 */
@Entity
@Table(name = "sub_work_stts_hstry")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SubWorkStatusHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sub_work_stts_hstry_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sub_work_id", nullable = false, updatable = false)
    private SubWorkEntity subWork;

    @Enumerated(EnumType.STRING)
    @Column(name = "bfr_work_stts_cd", nullable = false, length = 20, updatable = false)
    private WorkStatus previousWorkStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "aftr_work_stts_cd", nullable = false, length = 20, updatable = false)
    private WorkStatus nextWorkStatus;

    /*
     * 전이를 수행한 회원. 조회·매핑 전용 연관이며 회원의 상태를 여기서 바꾸지 않는다
     * (개발지침서 DB-10·AR-07).
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "prfmr_id", nullable = false, updatable = false)
    private MemberEntity performer;

    // 반려에만 사유가 있다. 착수·검토요청·승인은 사유 없이 일어난다
    @Column(name = "chg_rsn", columnDefinition = "TEXT", updatable = false)
    private String changeReason;

    @Column(name = "chg_dt", nullable = false, updatable = false)
    private Instant changedAt;

    public static SubWorkStatusHistoryEntity record(
            SubWorkEntity subWork,
            WorkStatus previousWorkStatus,
            WorkStatus nextWorkStatus,
            MemberEntity performer,
            String changeReason,
            Instant changedAt) {
        return new SubWorkStatusHistoryEntity(
                null,
                subWork,
                previousWorkStatus,
                nextWorkStatus,
                performer,
                changeReason,
                changedAt);
    }
}
