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
        if (!transition.isAllowedFrom(this.status)) {
            throw new GeneralException(
                    AcademicProgramErrorCode.INVALID_ACADEMIC_PROGRAM_TRANSITION);
        }
        this.status = transition.targetStatus();
    }
}
