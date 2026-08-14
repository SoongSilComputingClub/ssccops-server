package org.sscc.ssccopsserver.domain.operation.entity;

import java.math.BigDecimal;
import java.time.Instant;

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
 * sub_work(하위 업무) — 실제 실행 단위. work와 마찬가지로 oper(운영)의 확장 테이블이라
 * 제목·기간·담당자·우선순위는 자기 oper가 갖고, 여기에는 하위 업무 고유 속성만 둔다.
 *
 * 데이터사전은 sub_work_id를 'PK=FK'로 적고 있으나 oper_id를 별도 FK 컬럼으로도 정의한다.
 * 상속 매핑을 쓰지 않는 work의 선례를 따라 sub_work_id를 자체 PK로 두고 @MapsId를 쓰지 않는다.
 *
 * sub_work_ttl은 oper_ttl과 값이 같다. 화면의 제목 입력란이 하나뿐인데 두 컬럼 모두
 * NOT NULL이라 같은 값을 넣는다. 제목 수정이 붙으면 두 컬럼을 함께 바꿔야 한다.
 *
 * work_id(상위 업무)는 NOT NULL이다. 데이터사전 비고에 'NULL 허용'이 남아 있으나
 * Not Null 여부 컬럼이 Y이고, 화면도 "하위 업무는 상위 업무 안에서만 생성됩니다"라고
 * 못박고 있으며 API 정의서 OPS-007의 workId도 필수다.
 */
/*
 * 인덱스는 목록 조회(OPS-008)의 필터·정렬 컬럼에 건다 (DB-17). 마감 일시는 정렬 기본 키이자
 * 지연·마감임박 필터의 조건이고, 두 상태 코드는 화면 필터 칩이 그대로 거는 조건이다.
 * work_id는 상위 업무 목록(OPS-020)이 진행률·하위 업무 건수를 집계할 때 매 요청 타는 축이다 —
 * PostgreSQL은 FK에 인덱스를 자동으로 만들지 않는다.
 *
 * 주의: 이 선언으로 인덱스가 만들어지는 것은 ddl-auto가 도는 local·dev·test뿐이다.
 * prod는 ddl-auto가 none이라 배포 전에 아래 DDL을 직접 실행해야 한다.
 *   CREATE INDEX idx_sub_work_ddln_dt ON sub_work (ddln_dt);
 *   CREATE INDEX idx_sub_work_work_stts_cd ON sub_work (work_stts_cd);
 *   CREATE INDEX idx_sub_work_aprv_stts_cd ON sub_work (aprv_stts_cd);
 *   CREATE INDEX idx_sub_work_work_id ON sub_work (work_id);
 */
@Entity
@Table(
        name = "sub_work",
        indexes = {
            @Index(name = "idx_sub_work_ddln_dt", columnList = "ddln_dt"),
            @Index(name = "idx_sub_work_work_stts_cd", columnList = "work_stts_cd"),
            @Index(name = "idx_sub_work_aprv_stts_cd", columnList = "aprv_stts_cd"),
            @Index(name = "idx_sub_work_work_id", columnList = "work_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SubWorkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sub_work_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_id", nullable = false)
    private WorkEntity work;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "oper_id", nullable = false)
    private OperationEntity operation;

    @Column(name = "sub_work_ttl", nullable = false, length = 256)
    private String title;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sub_work_type_id", nullable = false)
    private SubWorkTypeEntity subWorkType;

    @Enumerated(EnumType.STRING)
    @Column(name = "work_stts_cd", nullable = false, length = 20)
    private WorkStatus workStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "aprv_stts_cd", nullable = false, length = 20)
    private ApprovalStatus approvalStatus;

    // 무엇을 하는 하위 업무인지. 현재 유효한 계획만 담고 변경 이력은 남기지 않는다
    @Column(name = "work_cn", columnDefinition = "TEXT")
    private String content;

    // 완료 기준 서술. 화면에 입력란이 없어 등록 시점에는 비어 있고, 완료 판정은 체크리스트가 맡는다
    @Column(name = "cmptn_crtr_cn", columnDefinition = "TEXT")
    private String completionCriteria;

    // 지연 여부는 마감 일시를 기준으로 자동 판정할 값이라 등록 시점에는 항상 false다
    @Column(name = "dly_yn", nullable = false)
    private boolean delayed;

    @Column(name = "otsd_url_addr", length = 200)
    private String externalLink;

    @Column(name = "ddln_dt")
    private Instant dueAt;

    @Column(name = "cmptn_dt")
    private Instant completedAt;

    /*
     * 하위 업무 등록(OPS-007)용 생성 팩토리. 상태는 항상 PLANNING(기획)이고 지연 여부는
     * false, 완료 일시는 NULL로 서버가 고정하며 클라이언트가 지정할 수 없다.
     *
     * 승인 상태는 유형의 승인 필요 여부만으로 정한다. 승인이 필요 없는 유형(REQ-016
     * 저위험 업무)은 NOT_REQUIRED로 시작해 승인 절차를 아예 타지 않고, 필요한 유형은
     * 아직 승인받지 않았다는 뜻의 PENDING으로 시작한다. 승인 대기 목록(OPS-017)에
     * 실제로 뜨는 시점은 검토요청 전이(TR-02) 이후이므로, 목록 조회는 업무 상태와
     * 승인 상태를 함께 걸러야 한다.
     */
    public static SubWorkEntity create(
            WorkEntity work,
            OperationEntity operation,
            SubWorkTypeEntity subWorkType,
            String title,
            String content,
            String externalLink,
            Instant dueAt) {
        return new SubWorkEntity(
                null,
                work,
                operation,
                title,
                subWorkType,
                WorkStatus.PLANNING,
                subWorkType.isApprovalNeeded()
                        ? ApprovalStatus.PENDING
                        : ApprovalStatus.NOT_REQUIRED,
                content,
                null,
                false,
                externalLink,
                dueAt,
                null);
    }

    /*
     * 상태 전이(OPS-010). 전이표(TR-01~TR-04)에 있는 조합만 통과하고 나머지는 전부 막는다
     * (BR-O03·VR-O04·LY-14). 상태를 직접 쓰는 setter를 열지 않는 것이 이 통제의 전제다 (AR-10).
     *
     * completionCriteriaMet(완료 체크리스트 충족 여부)과 agreedVoteCount(이번 회차 찬성 수)는
     * 다른 테이블(sub_work_chck_list·sub_work_aprv_vote)에 있어 엔티티가 스스로 셀 수 없다.
     * 사실만 넘겨받고, 그 사실로 전이를 막을지는 여기서 정한다.
     *
     * 수행자가 승인자인지는 여기서 보지 않는다 — 회원의 역할은 회원 도메인에 있고, 그 판정은
     * 역할 인가(#9)가 붙으면 통째로 옮겨갈 관심사라 서비스의 ApprovalAuthorityPolicy가 맡는다.
     */
    public void applyTransition(
            TransitionAction action,
            String reason,
            boolean completionCriteriaMet,
            long agreedVoteCount,
            Instant occurredAt) {
        switch (action) {
            case START -> start();
            case REQUEST_REVIEW -> requestReview();
            case APPROVE_COMPLETE ->
                    approveAndComplete(completionCriteriaMet, agreedVoteCount, occurredAt);
            case REJECT -> reject(reason);
        }
    }

    // TR-01 착수. 승인 상태는 건드리지 않는다 — 승인 절차는 검토요청부터 시작한다
    private void start() {
        requireStatus(WorkStatus.PLANNING);
        this.workStatus = WorkStatus.IN_PROGRESS;
    }

    /*
     * TR-02 검토요청. 승인이 필요한 유형이면 여기서 승인 대기가 발생한다.
     * 직전에 반려된 건이 다시 올라온 것이라면 대기가 아니라 재승인필요로 둔다 —
     * 승인자가 승인함(OPS-017)에서 '처음 올라온 건'과 '반려 후 다시 올라온 건'을 구분해야 한다.
     */
    private void requestReview() {
        requireStatus(WorkStatus.IN_PROGRESS);
        this.workStatus = WorkStatus.REVIEW;
        if (subWorkType.isApprovalNeeded()) {
            this.approvalStatus =
                    this.approvalStatus == ApprovalStatus.REJECTED
                            ? ApprovalStatus.REAPPROVAL_REQUIRED
                            : ApprovalStatus.PENDING;
        }
    }

    /*
     * TR-03 승인·완료. 승인과 완료가 한 단계다 — 승인 없이 완료하는 경로를 따로 두지 않는다.
     *
     * 완료 체크리스트를 다 채우지 못한 건은 완료되지 않는다 (REQ-021). 승인이 필요 없는
     * 유형(REQ-016 저위험 면제)은 승인 상태를 NOT_REQUIRED 그대로 두고 완료만 시킨다.
     *
     * 정족수 유형은 찬성 수가 min_need_agre_cnt에 이른 뒤에야 승인할 수 있고, 미달이면
     * QUORUM_NOT_MET(409)다 (POL-007 O-03 확정 · TR-03 · #47). 정족수는 승인자를 대체하는
     * 경로가 아니라 그 승인의 선행 조건이다 — 찬성이 다 모여도 이 메서드가 불리지 않으면
     * 완료되지 않고, 승인자라도 정족수 전에는 여기서 막힌다.
     *
     * 검사 순서는 정족수 → 체크리스트다. 정족수 미달은 담당자가 손쓸 수 없는 조건이라
     * 먼저 알려야 하고, 체크리스트는 승인자가 아니라 담당자가 채우는 값이다.
     *
     * 정족수·승인자 설정은 하위 업무에 복사돼 있지 않고 유형에서 그때그때 읽는다. 등록 시점에
     * 복사되는 것은 승인 필요 여부(aprv_stts_cd의 초기값)와 완료 점검 항목뿐이라, 유형의
     * 정족수를 바꾸면 이미 검토 중인 건의 승인 문턱도 함께 바뀐다 (#43의 소급 금지는
     * '이미 저장된 값을 건드리지 않는다'는 뜻이며, 저장되지 않은 값까지 얼리지는 않는다).
     */
    private void approveAndComplete(
            boolean completionCriteriaMet, long agreedVoteCount, Instant completedAt) {
        requireStatus(WorkStatus.REVIEW);
        if (subWorkType.requiresQuorum() && agreedVoteCount < subWorkType.getMinAgreeCount()) {
            throw new GeneralException(OperationErrorCode.QUORUM_NOT_MET);
        }
        if (!completionCriteriaMet) {
            throw new GeneralException(OperationErrorCode.COMPLETION_CRITERIA_UNMET);
        }
        this.workStatus = WorkStatus.DONE;
        this.completedAt = completedAt;
        if (subWorkType.isApprovalNeeded()) {
            this.approvalStatus = ApprovalStatus.APPROVED;
        }
    }

    /*
     * TR-04 반려. 상태가 진행으로 되돌아간다. 체크리스트 체크 상태는 초기화하지 않는다 —
     * 되돌아간 담당자가 남은 항목만 마저 채우는 것이 화면의 흐름이다.
     *
     * 사유는 필수이며 공백만 있는 문자열도 거부한다 (VR-O06). 승인이 필요 없는 유형은
     * 승인 상태를 바꾸지 않는다 — 반려 사실은 sub_work_rjct에 남는다.
     */
    private void reject(String reason) {
        requireStatus(WorkStatus.REVIEW);
        if (reason == null || reason.isBlank()) {
            throw new GeneralException(OperationErrorCode.REASON_REQUIRED);
        }
        this.workStatus = WorkStatus.IN_PROGRESS;
        if (subWorkType.isApprovalNeeded()) {
            this.approvalStatus = ApprovalStatus.REJECTED;
        }
    }

    /*
     * 완료 체크리스트를 지금 바꿀 수 있는지 (OPS-013). 완료된 건은 막는다 — 완료 후 체크를
     * 해제하면 '완료됐는데 완료 조건 미충족'인 데이터가 남고, 완료를 되돌리는 전이는 전이표에
     * 없다(TR-X1). 기획·진행·검토에서는 모두 허용한다: 반려로 진행에 되돌아온 담당자가 남은
     * 항목을 마저 채우는 것이 화면의 흐름이라 검토 단계로 좁히지 않는다.
     *
     * 전용 오류 코드를 새로 만들지 않고 TRANSITION_NOT_ALLOWED를 재사용한다. 정의서
     * 03_오류_코드에 없는 코드를 늘리면 코드 문자열로 분기하는 프론트와 어긋난다.
     */
    public void requireChecklistEditable() {
        if (this.workStatus == WorkStatus.DONE) {
            throw new GeneralException(OperationErrorCode.TRANSITION_NOT_ALLOWED);
        }
    }

    /*
     * 지금 찬반 투표를 받을 수 있는지 (OPS-015 · #47). 세 가지가 모두 성립해야 한다.
     *  1. 정족수 유형이어야 한다 — 단독·승인 불필요 유형은 셀 대상이 없다. 승인함 화면이
     *     네 카드에 모두 찬성·반대 버튼을 그리고 있으나, 버튼 구성이 계약은 아니다.
     *  2. 검토(REVIEW) 상태여야 한다 — 아직 올라오지 않았거나 이미 끝난 건에 표를 던질 수 없다.
     *  3. 승인 대기 중이어야 한다(대기·재승인필요) — 이미 승인·반려된 건은 이번 회차가 닫혔다.
     *
     * 전용 오류 코드를 만들지 않고 TRANSITION_NOT_ALLOWED를 재사용한다. 정의서 03_오류_코드에
     * 없는 코드를 늘리면 코드 문자열로 분기하는 프론트와 어긋난다 (requireChecklistEditable 선례).
     */
    public void requireVotable() {
        boolean awaitingApproval =
                this.approvalStatus == ApprovalStatus.PENDING
                        || this.approvalStatus == ApprovalStatus.REAPPROVAL_REQUIRED;
        if (!subWorkType.requiresQuorum()
                || this.workStatus != WorkStatus.REVIEW
                || !awaitingApproval) {
            throw new GeneralException(OperationErrorCode.TRANSITION_NOT_ALLOWED);
        }
    }

    private void requireStatus(WorkStatus required) {
        if (this.workStatus != required) {
            throw new GeneralException(OperationErrorCode.TRANSITION_NOT_ALLOWED);
        }
    }

    /*
     * 진행률(OPS-003의 하위 업무 목록). sub_work에는 진행률 컬럼이 없으므로 완료 체크리스트의
     * 완료 비율에서 파생한다. 체크리스트는 다른 테이블(sub_work_chck_list)에 있어 엔티티가
     * 스스로 셀 수 없으므로 개수만 넘겨받고, 완료 여부로 100을 확정하는 판단은 여기서 한다.
     */
    public BigDecimal progressRate(long completedItems, long totalItems) {
        return ProgressRate.ofChecklist(
                this.workStatus == WorkStatus.DONE, completedItems, totalItems);
    }

    /*
     * 마감이 지났는데 아직 완료되지 않았는지. dly_yn 컬럼을 읽지 않고 그때그때 판정한다 —
     * 컬럼은 등록 시 false로 고정된 뒤 갱신하는 주체가 없어 항상 false이기 때문이다.
     * 컬럼을 실제로 채우는 배치는 목록 조회(OPS-008)의 마감 임박·지연 필터가 인덱스를
     * 필요로 할 때 붙는다.
     *
     * 마감이 없는 하위 업무는 지연될 수 없고, 완료된 하위 업무는 늦게 끝났더라도 지연이 아니다
     * — 화면이 이 값으로 '지금 손봐야 하는 건'을 표시하기 때문이다.
     */
    public boolean isDelayedAt(Instant now) {
        return dueAt != null && workStatus != WorkStatus.DONE && dueAt.isBefore(now);
    }
}
