package org.sscc.ssccopsserver.domain.operation.entity;

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

import org.sscc.ssccopsserver.domain.operation.code.error.OperationErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

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
 *
 * 진행률은 저장하지 않는다 (AGG-05 · #117 · V25). 정본은 AGG-01 — 하위 업무 진행률(체크리스트
 * 완료율)의 단순 평균이며 조회 API가 그때그때 계산한다(ProgressRate.average). 한때 있던 저장
 * 컬럼 work_prgrs_rt는 '완료 하위 업무 수 ÷ 전체'라는 다른 식으로 채워져 DB를 보는 사람과 화면이
 * 다른 숫자를 봤고, 갱신을 걷어낸 뒤로는 등록 시의 0에 머물렀다. 저장 컬럼을 되살리지 말 것 —
 * 같은 어긋남이 그대로 돌아온다.
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
     * 업무 등록(OPS-002)용 생성 팩토리. 상태는 항상 PLANNING(기획)으로 서버가 고정하며
     * 클라이언트가 지정할 수 없다.
     */
    public static WorkEntity create(
            OperationEntity operation, WorkType workType, String generalReview) {
        return new WorkEntity(null, operation, workType, WorkStatus.PLANNING, generalReview);
    }

    public void changeWorkType(WorkType workType) {
        this.workType = workType;
    }

    public void writeGeneralReview(String generalReview) {
        this.generalReview = generalReview;
    }

    /*
     * 상태 전이 (#622 · ssccops#563). 전이표에 있는 조합만 통과하고 나머지는 전부
     * TRANSITION_NOT_ALLOWED(409)다.
     *
     * 완료가 아닌 하위 업무 수는 다른 테이블(sub_work)에 있어 엔티티가 스스로 셀 수 없으므로
     * 사실만 넘겨받는다 — 회의 종료(MeetingEntity.close)가 미처리 안건 여부를 받는 것과 같은
     * 경계다. 수는 완료 전이에서만 쓰며, 다른 전이에서는 서비스가 세지 않고 0을 넘긴다.
     */
    public void applyTransition(WorkTransitionAction action, long unfinishedSubWorkCount) {
        switch (action) {
            case START -> move(WorkStatus.PLANNING, WorkStatus.IN_PROGRESS);
            case REQUEST_REVIEW -> move(WorkStatus.IN_PROGRESS, WorkStatus.REVIEW);
            case COMPLETE -> complete(unfinishedSubWorkCount);
            case REVERT_REVIEW -> move(WorkStatus.REVIEW, WorkStatus.IN_PROGRESS);
            case REOPEN -> move(WorkStatus.DONE, WorkStatus.IN_PROGRESS);
        }
    }

    /*
     * 완료. 하위 업무가 하나라도 완료가 아니면 막는다(하위 업무 0건이면 통과) — 운영진이 «경고만»
     * 대신 고른 규칙이다(ssccops#563 · 남은 일이 완료 업무 밑에 묻힌다). 남은 수는 오류 메시지에
     * 실어 화면이 그대로 보여 줄 수 있게 한다. 상태 검사를 먼저 하는 것은 «검토가 아닌 업무»에는
     * 남은 수보다 순서 위반이 먼저 할 말이기 때문이다.
     */
    private void complete(long unfinishedSubWorkCount) {
        requireStatus(WorkStatus.REVIEW);
        if (unfinishedSubWorkCount > 0) {
            throw new GeneralException(
                    OperationErrorCode.SUB_WORK_UNFINISHED,
                    "완료되지 않은 하위 업무가 " + unfinishedSubWorkCount + "건 남아 있습니다.");
        }
        this.workStatus = WorkStatus.DONE;
    }

    private void move(WorkStatus from, WorkStatus to) {
        requireStatus(from);
        this.workStatus = to;
    }

    private void requireStatus(WorkStatus required) {
        if (this.workStatus != required) {
            throw new GeneralException(OperationErrorCode.TRANSITION_NOT_ALLOWED);
        }
    }
}
