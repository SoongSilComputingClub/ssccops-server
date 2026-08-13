package org.sscc.ssccopsserver.domain.operation.entity;

import java.math.BigDecimal;
import java.math.RoundingMode;

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

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * work(업무) — oper(운영)의 확장 테이블. 제목·기간·담당자 같은 공통 속성은 부모 oper가 갖고,
 * 여기에는 업무 고유 속성만 둔다.
 *
 * work_id를 자체 PK로 가지며 oper_id는 일반 FK다. PK=FK 상속이 아니므로 @MapsId를 쓰지 않는다.
 * 등록자·등록시각은 부모 oper의 crt_dt·mdfcn_dt가 보유하므로 여기서 중복 기록하지 않는다.
 */
@Entity
@Table(name = "work")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class WorkEntity {

    // 업무 진행률 초기값. 하위 업무가 하나도 없는 동안은 0이다
    private static final BigDecimal INITIAL_PROGRESS_RATE = BigDecimal.ZERO;

    private static final BigDecimal PERCENT_MULTIPLIER = BigDecimal.valueOf(100);
    private static final int PROGRESS_RATE_SCALE = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "work_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "oper_id", nullable = false)
    private OperationEntity operation;

    @Enumerated(EnumType.STRING)
    @Column(name = "work_type_cd", nullable = false, length = 20)
    private WorkType workType;

    @Enumerated(EnumType.STRING)
    @Column(name = "work_stts_cd", nullable = false, length = 20)
    private WorkStatus workStatus;

    // 행사 종료 후 회고. 등록 화면에도 입력란이 있으나 선택 입력이라 보통 비어 있다
    @Column(name = "grvw_cn", columnDefinition = "TEXT")
    private String generalReview;

    @Column(name = "work_prgrs_rt", nullable = false, precision = 5, scale = 2)
    private BigDecimal progressRate;

    /*
     * 업무 등록(OPS-002)용 생성 팩토리. 상태는 항상 PLANNING(기획), 진행률은 0으로
     * 서버가 고정하며 클라이언트가 지정할 수 없다.
     */
    public static WorkEntity create(
            OperationEntity operation, WorkType workType, String generalReview) {
        return new WorkEntity(
                null,
                operation,
                workType,
                WorkStatus.PLANNING,
                generalReview,
                INITIAL_PROGRESS_RATE);
    }

    public void changeWorkType(WorkType workType) {
        this.workType = workType;
    }

    public void writeGeneralReview(String generalReview) {
        this.generalReview = generalReview;
    }

    /*
     * 하위 업무 완료 개수로 진행률을 다시 계산한다. 등록 화면의 "하위 업무를 이 운영에
     * 연결하면 진행률이 자동으로 집계됩니다" 안내가 이 계산을 가리킨다.
     *
     * 하위 업무가 하나도 없으면 0이다. 완료율은 백분율(0.00~100.00)로 소수 2자리까지
     * 반올림해 저장한다 — work_prgrs_rt가 NUMERIC(5,2)이라 그 이상은 담기지 않는다.
     */
    public void recalculateProgressRate(long completedCount, long totalCount) {
        if (totalCount <= 0) {
            this.progressRate = INITIAL_PROGRESS_RATE;
            return;
        }
        this.progressRate =
                BigDecimal.valueOf(completedCount)
                        .multiply(PERCENT_MULTIPLIER)
                        .divide(
                                BigDecimal.valueOf(totalCount),
                                PROGRESS_RATE_SCALE,
                                RoundingMode.HALF_UP);
    }
}
