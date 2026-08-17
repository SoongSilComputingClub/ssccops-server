package org.sscc.ssccopsserver.domain.operation.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
/*
 * 인덱스는 목록 조회(OPS-020)의 필터 두 축이다 (DB-17). 정렬 키는 여기가 아니라 oper에
 * 있으므로(등록 일시·시작 일시) 한 인덱스로 필터와 정렬을 함께 덮을 수 없다 —
 * 두 테이블로 나뉜 구조의 대가이며, 정렬 쪽 인덱스는 OperationEntity에 있다.
 *
 * 주의: prod도 ddl-auto가 update이므로(정식 버전 전까지 한시적) 이 선언은 배포 때 반영된다.
 * update는 추가만 하고 삭제·이름 변경·타입 변경은 반영하지 않으니, 아래 DDL은 그런 변경이
 * 필요할 때와 정식 버전에서 ddl-auto를 none으로 되돌린 뒤를 위한 기준으로 남긴다.
 *   CREATE INDEX idx_work_work_stts_cd ON work (work_stts_cd);
 *   CREATE INDEX idx_work_work_type_cd ON work (work_type_cd);
 */
@Entity
@Table(
        name = "work",
        indexes = {
            @Index(name = "idx_work_work_stts_cd", columnList = "work_stts_cd"),
            @Index(name = "idx_work_work_type_cd", columnList = "work_type_cd")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class WorkEntity {

    // 업무 진행률 초기값. 등록 이후로는 갱신되지 않으므로 이 컬럼은 늘 0이다 (AGG-05)
    private static final BigDecimal INITIAL_PROGRESS_RATE = BigDecimal.ZERO;

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

    /*
     * 채우지 않기로 결정한 컬럼 (AGG-05, #117). 등록 시 0으로 굳고 그 뒤 갱신하는 주체가 없다.
     *
     * 진행률의 정본은 AGG-01 — 하위 업무 진행률(체크리스트 완료율)의 단순 평균이며, 조회
     * API가 그때그때 계산해 내려준다(ProgressRate.average). 이 컬럼은 '완료 하위 업무 수 ÷
     * 전체'라는 다른 식으로 채워지고 있어 DB를 직접 보는 사람과 화면이 다른 숫자를 봤고,
     * 두 식 중 명세를 따르는 쪽은 응답이므로 저장 쪽 갱신을 걷어냈다.
     *
     * 갱신 코드를 다시 넣지 말 것 — 되살리면 같은 어긋남이 그대로 돌아온다. 컬럼 자체를
     * 지우지 않은 것은 ddl-auto: update가 삭제를 반영하지 않고 데이터사전 동기화가 따라붙기
     * 때문이며, 제거는 마이그레이션 도구(Flyway/Liquibase) 도입 시점의 일이다.
     */
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
}
