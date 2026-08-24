package org.sscc.ssccopsserver.domain.academicprogram.entity;

import java.time.LocalDate;

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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.sscc.ssccopsserver.domain.academicprogram.code.error.AcademicProgramErrorCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * session(회차 실적) — 계획(curriculum_item) 한 건에 대응하는 실제 진행 기록 (#135,
 * 학술관리_데이터모델.md §2·§3). 스터디장/팀장이 회차 종료 후 진행 내용과 출석을 적는다.
 *
 * curriculum_item_id UNIQUE가 "계획 1개당 실적 최대 1개"를 DB에서 강제한다. 그래서 이 행이
 * 없다는 사실 자체가 NOT_SUBMITTED라는 파생 상태이고(§3), session_stts_cd에 NOT_SUBMITTED가
 * 저장되는 일은 없다 — 행이 태어나는 순간 이미 SUBMITTED다. 빈 행을 미리 깔아 두지 않는 것은
 * "아직 아무도 손대지 않은 계획"이 훨씬 흔한 상태이기 때문이다.
 *
 * 감사 컬럼(crt_dt·mdfcn_dt)을 두지 않는다 — ERD(§2)에 없고, 재제출이 이력을 남기지 않는
 * 도메인이라 "언제 고쳤는가"를 이 행에 적어 봐야 그 시각 하나가 몇 번째 제출의 것인지 답할 수
 * 없다. 누가 언제 무엇을 바꿨는지는 감사 로그(#8)가 확정되면 그쪽으로 넘긴다(§7).
 */
@Entity
@Table(
        name = "session",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_session_curriculum_item",
                        columnNames = {"curriculum_item_id"}),
        /*
         * 활동 횡단 조회(#136)가 쓰는 두 컬럼이다. 승인 대기 목록은 활동 경계 없이
         * session_stts_cd = SUBMITTED만 골라 real_dt 순으로 읽으므로, 이 인덱스가 없으면
         * 회차가 쌓일수록 전체 스캔 뒤 정렬이 된다.
         *
         * 계획일(curriculum_item.plan_dt)이 아니라 진행일을 정렬 키로 두는 이유는
         * SessionSortOrder 주석에 있다(그쪽은 NULL을 허용해 커서 비교가 성립하지 않는다).
         */
        indexes = {
            @Index(name = "idx_session_stts_cd", columnList = "session_stts_cd"),
            @Index(name = "idx_session_real_dt", columnList = "real_dt")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "session_id")
    private Long id;

    /*
     * 대응하는 계획. updatable = false로 잠그지 않는 것은 재제출이 전체 교체이기 때문이다 —
     * 회차를 잘못 골라 기록한 것을 재제출에서 바로잡을 수 있어야 한다(SessionServiceImpl.resubmit).
     */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "curriculum_item_id", nullable = false)
    private CurriculumItemEntity curriculumItem;

    @Column(name = "real_dt", nullable = false)
    private LocalDate realDate;

    @Column(name = "cn", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "notice_cn", columnDefinition = "TEXT")
    private String noticeContent;

    @Enumerated(EnumType.STRING)
    @Column(name = "session_stts_cd", nullable = false, length = 30)
    private SessionStatus status;

    /*
     * 기록 작성자. 재제출 때 그때의 작성자로 갱신되므로 updatable = false가 아니다 — 이 행은
     * 이력이 아니라 "지금의 기록"이고, 그 기록을 쓴 사람이 누구인지가 이 컬럼의 뜻이다.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rgtr_mbr_id", nullable = false)
    private MemberEntity registrant;

    /*
     * 신규 제출(#135 · POST). 상태는 항상 SUBMITTED다 — NOT_SUBMITTED는 행이 없는 상태를
     * 가리키는 파생 값이라 저장될 수 없고, 제출과 동시에 국장 검토 대기로 들어간다(§3).
     */
    public static SessionEntity submit(
            CurriculumItemEntity curriculumItem,
            LocalDate realDate,
            String content,
            String noticeContent,
            MemberEntity registrant) {
        return new SessionEntity(
                null,
                curriculumItem,
                realDate,
                content,
                noticeContent,
                SessionStatus.SUBMITTED,
                registrant);
    }

    /*
     * 지금 이 회차를 다시 쓸 수 있는가. 판정은 SessionStatus.allowsRecording에 맡긴다 —
     * "어떤 상태에서 기록을 쓸 수 있는가"를 두 벌로 적지 않기 위해서다(#134가 그 어휘를 먼저
     * 세우며 남긴 지침). 행이 이미 있으므로 실제로 통과하는 값은 REVISION_REQUESTED 하나이고,
     * NOT_SUBMITTED는 행이 없는 상태를 가리키므로 여기서 볼 수 있는 값이 아니다.
     *
     * 서비스가 이 검사를 먼저 한 번 부르고 resubmit이 다시 부른다 — 성립하지 않는 재제출에
     * 출석 대상 검증 같은 뒷일을 먼저 시키면 엉뚱한 오류가 먼저 보이기 때문이고(
     * AcademicProgramEntity.changeStatus를 전이의 맨 앞에 두는 것과 같은 이유), 그렇다고
     * 규칙을 서비스에 옮겨 적으면 엔티티를 직접 부르는 다른 경로에서 빠진다.
     */
    public void requireResubmittable() {
        if (!this.status.allowsRecording()) {
            throw new GeneralException(AcademicProgramErrorCode.SESSION_NOT_EDITABLE);
        }
    }

    /*
     * 재제출(#135 · PUT). 이전 내용을 덮어쓰고 이력을 남기지 않는다(데이터모델 §7 확정 원칙) —
     * 마지막 수정요청 사유만 academic_program_aprv의 최신 행에 남는다.
     */
    public void resubmit(
            CurriculumItemEntity curriculumItem,
            LocalDate realDate,
            String content,
            String noticeContent,
            MemberEntity registrant) {
        requireResubmittable();
        this.curriculumItem = curriculumItem;
        this.realDate = realDate;
        this.content = content;
        this.noticeContent = noticeContent;
        this.registrant = registrant;
        this.status = SessionStatus.SUBMITTED;
    }

    /*
     * 학술국장의 승인·수정요청(#136). 전이표는 SessionTransition이 갖고, 여기서는 그 표를
     * 어겼을 때 무엇으로 거절할지만 맡는다 — AcademicProgramEntity.changeStatus·
     * FormEntity.changeStatus와 같은 역할 분담이다.
     *
     * 전이 가능 여부를 사유보다 먼저 본다. 이미 승인된 회차에 사유 없는 수정요청이 오면
     * 답해야 할 것은 "사유를 적어라"가 아니라 "이미 처리된 회차다"이기 때문이다.
     *
     * 승인 이력(academic_program_aprv)은 이 메서드가 남기지 않는다 — 호출부
     * (SessionReviewServiceImpl)가 같은 트랜잭션에서 남긴다(활동 전이와 같은 경계).
     */
    public void changeStatus(SessionTransition transition, String reason) {
        if (!transition.isAllowedFrom(this.status)) {
            throw new GeneralException(AcademicProgramErrorCode.INVALID_SESSION_TRANSITION);
        }
        if (transition.requiresReason() && (reason == null || reason.isBlank())) {
            throw new GeneralException(AcademicProgramErrorCode.REVISION_REASON_REQUIRED);
        }
        this.status = transition.targetStatus();
    }
}
