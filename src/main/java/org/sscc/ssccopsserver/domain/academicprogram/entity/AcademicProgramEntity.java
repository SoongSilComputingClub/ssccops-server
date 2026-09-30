package org.sscc.ssccopsserver.domain.academicprogram.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.sscc.ssccopsserver.domain.academicprogram.code.error.AcademicProgramErrorCode;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * acdm_actv(학술 활동) — event(행사)의 1:1 확장 (#131, 학술관리_데이터모델.md §1·§2).
 * 제목·기간 같은 공통 속성은 event가 갖고, 여기에는 커리큘럼·진행률 같은 학술 활동 전용
 * 필드만 둔다 — work가 oper를 확장하는 것과 같은 패턴이다.
 *
 * event_id UNIQUE인 것은 AcademicProgram이 Event의 확장이지 별도 생성 단위가 아니기 때문이다.
 * 신입회원 모집·홈커밍데이처럼 학술 활동이 아닌 Event는 이 테이블에 연결되지 않는다.
 *
 * 소프트 삭제를 두지 않는다(설계 결정 #2) — 반려는 이제 폼 응답 단계(#141)에서 끝나 이 행
 * 자체가 만들어지지 않으므로, 승인 이후 만들어진 행을 지울 유스케이스가 없다.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "acdm_actv",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_acdm_actv_event",
                    columnNames = {"event_id"}),
            @UniqueConstraint(
                    name = "uk_acdm_actv_form_rspns",
                    columnNames = {"form_rspns_id"})
        },
        indexes = {
            @Index(name = "idx_acdm_actv_stts_cd", columnList = "acdm_actv_stts_cd"),
            @Index(name = "idx_acdm_actv_prpsr_mbr_id", columnList = "prpsr_mbr_id"),
            @Index(name = "idx_acdm_actv_leadr_mbr_id", columnList = "leadr_mbr_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AcademicProgramEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "acdm_actv_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false, updatable = false)
    private EventEntity event;

    /*
     * 이 활동이 태어난 기획안(form_rspns_hstry.form_rspns_id, #150).
     *
     * **출처를 남기는 값이지 연결이 아니다.** 이관은 복사이고 역방향 동기화는 없다
     * (ssccops#148) — 이관 뒤에 폼 응답을 고쳐도 여기 만들어진 활동은 바뀌지 않는다. 승인이
     * 종결(#141)이라 응답이 더 바뀔 일도 사실상 없지만, 규칙은 "복사한다"이지 "따라간다"가
     * 아니다. 그래서 이 필드를 읽어 활동의 값을 채우는 코드를 만들지 말 것.
     *
     * UNIQUE(uk_acdm_actv_form_rspns)는 중복 이관 방어선이다. 같은 응답을 두 번 승인할
     * 수 없으므로(ACCEPTED는 종결) 정상 흐름에서는 걸릴 일이 없고, 데이터 정합성이 깨진 경우에만
     * 의미가 있다 — 선조회만으로는 동시 요청을 막지 못한다는 이 레포의 규칙 그대로다.
     *
     * NOT NULL로 붙일 수 있는 것은 **이 이슈 전까지 acdm_actv 행을 만드는 경로가 아예
     * 없었기 때문이다**(등록 API는 2026-08-23 설계 변경으로 사라졌고 승인 이관이 그 자리를
     * 대신한다). dev·prod의 ddl-auto: update는 이미 행이 있는 테이블에 기본값 없는 NOT NULL
     * 컬럼을 붙이지 못하는데, 그 테이블은 어느 환경에서도 비어 있다.
     */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "form_rspns_id", nullable = false, updatable = false)
    private FormResponseHistoryEntity formResponse;

    /** 스터디/프로젝트 구분(#130 코드테이블). 승인 이후에는 바꿀 수 없다(S2에서 거부) */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "acdm_actv_type_cd", nullable = false)
    private AcademicProgramTypeEntity type;

    @Enumerated(EnumType.STRING)
    @Column(name = "acdm_actv_stts_cd", nullable = false, length = 20)
    private AcademicProgramStatus status;

    @Column(name = "goal_cn", nullable = false, columnDefinition = "TEXT")
    private String goalContent;

    @Column(name = "prep_cn", columnDefinition = "TEXT")
    private String prepContent;

    @Column(name = "schdl_cn", length = 500)
    private String scheduleText;

    @Column(name = "pscp_min_cnt")
    private Integer capacityMinCount;

    @Column(name = "pscp_max_cnt")
    private Integer capacityMaxCount;

    /** 기획안 제출자. 사후 변경 불가라 updatable = false로 잠근다 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "prpsr_mbr_id", nullable = false, updatable = false)
    private MemberEntity proposer;

    /*
     * 스터디장/팀장. 생성 시점부터 prpsrMbrId와 같은 값으로 채워지며 NOT NULL이다(#133,
     * 2026-08-24 재설계 — 승인이 곧 생성이라 "승인 전" 구간 자체가 없다). 정적 권한 코드가
     * 아니라 이 필드 본인 여부로 "이 활동 한정" 소유권을 판정한다(AcademicProgramOwnershipPolicy,
     * 학술관리_데이터모델.md §5).
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "leadr_mbr_id", nullable = false)
    private MemberEntity leader;

    @CreatedDate
    @Column(name = "crt_dt", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "mdfcn_dt", nullable = false)
    private Instant updatedAt;

    /*
     * 생성 팩토리. 상태는 항상 APPROVED이고 리더는 항상 proposer와 같은 값이다(#133,
     * 2026-08-24 재설계) — 승인이 곧 생성이므로 "승인 전" 구간 자체가 없다.
     *
     * 이 팩토리를 부르는 자리는 #131이 아니다 — 기획안 접수는 폼 도메인이 맡고, 폼 응답이
     * 승인될 때 서버가 Event·AcademicProgram·CurriculumItem을 만드는 이관(ssccops#148,
     * #150)이 실제 호출부다. 생성 직후 같은 트랜잭션에서 승인 후속 처리
     * (AcademicProgramApprovalEffectsService, #133)가 스터디장/팀장 역할 부여와 빈 모집 폼
     * 생성을 이어서 한다. #131은 이렇게 만들어진 행을 조회만 한다.
     */
    public static AcademicProgramEntity create(
            EventEntity event,
            FormResponseHistoryEntity formResponse,
            AcademicProgramTypeEntity type,
            String goalContent,
            String prepContent,
            String scheduleText,
            Integer capacityMinCount,
            Integer capacityMaxCount,
            MemberEntity proposer) {
        return new AcademicProgramEntity(
                null,
                event,
                formResponse,
                type,
                AcademicProgramStatus.APPROVED,
                goalContent,
                prepContent,
                scheduleText,
                capacityMinCount,
                capacityMaxCount,
                proposer,
                proposer,
                null,
                null);
    }

    /*
     * 상태 전이(#133 · POST /v1/academic-programs/{id}/transitions). 전이표는
     * AcademicProgramTransition이 갖고, 여기서는 그 표를 어겼을 때 무엇으로 거절할지만 맡는다 —
     * FormEntity.changeStatus와 같은 역할 분담이다.
     *
     * 전이 이력은 이 메서드가 남기지 않는다 — APPROVE_COMPLETION의 acdm_actv_aprv 기록은
     * 호출부(AcademicProgramServiceImpl.transition)가 별도로 남긴다. START_RECRUITMENT는 폼
     * 전이·모집 기간 반영과 한 트랜잭션으로 오케스트레이션되므로 그 부수 효과도 호출부가 맡는다.
     */
    public void changeStatus(AcademicProgramTransition transition) {
        changeStatus(transition, null);
    }

    /*
     * 사유가 딸린 전이(#611 · DISCONTINUE는 사유가 필수다). 전이 가능 여부를 사유보다 먼저 본다 —
     * 이미 종료된 활동에 사유 없는 폐지가 오면 답해야 할 것은 «사유를 적어라»가 아니라 «그
     * 상태에서는 폐지할 수 없다»다(SessionEntity.changeStatus와 같은 순서). 사유는 여기 저장하지
     * 않는다 — 남는 자리는 승인 이력(acdm_actv_aprv.opnn_cn)이고 호출부가 같은 트랜잭션에서 쓴다.
     *
     * REINSTATE는 여기로 오지 않는다 — 목적 상태를 폐지 이력에서 받아야 해서 reinstate가 따로 있다.
     */
    public void changeStatus(AcademicProgramTransition transition, String reason) {
        if (!transition.hasFixedTarget()) {
            throw new IllegalArgumentException(
                    transition + "는 reinstate로 부른다 — 목적 상태를 폐지 이력에서 받아야 한다");
        }
        requireAllowed(transition);
        if (transition.requiresReason() && (reason == null || reason.isBlank())) {
            throw new GeneralException(AcademicProgramErrorCode.DISCONTINUATION_REASON_REQUIRED);
        }
        this.status = transition.targetStatus();
    }

    /*
     * 복원(#611 REINSTATE · ADR-0058) — 폐지 전 상태로 되돌린다. 그 상태는 **추론하지 않고** 폐지
     * 줄이 남긴 값(bfr_acdm_actv_stts_cd)을 받는다: 모집 시작은 이력 줄을 남기지 않고 모집 폼
     * 상태는 폼 화면에서도 바뀌어, 둘 다 «폐지 전에 모집을 시작했었나»의 증거가 못 된다.
     *
     * 폐지된 활동이 아니면 409가 먼저다(changeStatus와 같은 순서). 넘겨받은 값이 폐지가 출발할 수
     * 있는 상태(승인·진행 중)가 아니면 정합성이 깨진 것이라 IllegalStateException이다 — 조용히
     * 진행 중으로 되돌리면 모집이 열린 적 없는 «진행 중»이 생긴다.
     */
    public void reinstate(AcademicProgramStatus statusBeforeDiscontinuation) {
        requireAllowed(AcademicProgramTransition.REINSTATE);
        if (statusBeforeDiscontinuation == null
                || !AcademicProgramTransition.DISCONTINUE.isAllowedFrom(
                        statusBeforeDiscontinuation)) {
            throw new IllegalStateException(
                    "폐지 이력에 되돌아갈 상태가 없다. academicProgramId="
                            + id
                            + ", statusBeforeDiscontinuation="
                            + statusBeforeDiscontinuation);
        }
        this.status = statusBeforeDiscontinuation;
    }

    private void requireAllowed(AcademicProgramTransition transition) {
        if (!transition.isAllowedFrom(this.status)) {
            throw new GeneralException(
                    AcademicProgramErrorCode.INVALID_ACADEMIC_PROGRAM_TRANSITION);
        }
    }

    /*
     * 지연(#610) — **진행 중인데 예정된 운영 기간(event_end_dt)이 지났고 진행률이 100% 미만.**
     * 학술국장이 정한 정의다(ssccops#551). 저장하지 않고 조회 시점에 판정한다 — 날짜가 지나는
     * 것만으로 값이 바뀌어 컬럼으로 두면 스케줄러가 필요해진다(SubWorkEntity.isDelayedBefore와
     * 같은 이유).
     *
     * **목록 필터(AcademicProgramRepositoryImpl의 DELAYED)가 같은 조건을 JPQL로 옮겨 쓴다** —
     * 규칙을 바꾸면 두 곳을 함께 고친다. AcademicProgramControllerTest가 두 결과를 대조한다.
     *
     * - 진행 중(ONGOING)만 — 모집 전(APPROVED)은 시작도 하지 않아 지연이 아니라 폐지 후보이고
     *   (ssccops#552), 종료(COMPLETED)·폐지(DISCONTINUED · #611)는 이미 학술국장이 멈춘 것이다.
     * - 종료일이 없으면 지연될 수 없다. 이관이 기획안 필수 문항에서 채우므로 실제로는 없어야 한다.
     * - 경계는 '지금'이다. 이관이 종료를 그날의 끝(AcademicProgramMigrationServiceImpl.endOfDay)
     *   으로 저장하므로 종료일 당일은 아직 지연이 아니고 다음 날부터 지연이다.
     * - «100% 미만»은 비율이 아니라 **개수**로 본다 — 반올림한 비율(소수 2자리)은 항목이 아주
     *   많으면 한 개가 남아도 100.00이 되어, 목록 필터(개수 비교)와 갈린다. 계획 항목이 0개면
     *   진행률이 0이라 지연이다(AcademicProgramProgressResponse와 같은 정의).
     *
     * 개수를 넘겨받는 것은 엔티티가 스스로 셀 수 없어서다(SubWorkEntity.isReadyForReview와 같다).
     */
    public boolean isDelayedAt(Instant now, long curriculumItemCount, long approvedSessionCount) {
        Instant endAt = event.getEndAt();
        boolean planCompleted =
                curriculumItemCount > 0 && approvedSessionCount >= curriculumItemCount;
        return status == AcademicProgramStatus.ONGOING
                && endAt != null
                && endAt.isBefore(now)
                && !planCompleted;
    }
}
