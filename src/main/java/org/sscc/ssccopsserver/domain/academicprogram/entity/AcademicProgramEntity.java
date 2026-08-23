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
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * academic_program(학술 활동) — event(행사)의 1:1 확장 (#131, 학술관리_데이터모델.md §1·§2).
 * 제목·기간 같은 공통 속성은 event가 갖고, 여기에는 커리큘럼·진행률 같은 학술 활동 전용
 * 필드만 둔다 — work가 oper를 확장하는 것과 같은 패턴이다.
 *
 * event_id UNIQUE인 것은 AcademicProgram이 Event의 확장이지 별도 생성 단위가 아니기 때문이다.
 * 신입회원 모집·홈커밍데이처럼 학술 활동이 아닌 Event는 이 테이블에 연결되지 않는다.
 *
 * 소프트 삭제를 두지 않는다(설계 결정 #2) — REJECTED가 종결 상태 역할을 하므로 물리 삭제
 * 유스케이스가 없다.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "academic_program",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_academic_program_event",
                        columnNames = {"event_id"}),
        indexes = {
            @Index(name = "idx_academic_program_stts_cd", columnList = "academic_program_stts_cd"),
            @Index(name = "idx_academic_program_prpsr_mbr_id", columnList = "prpsr_mbr_id"),
            @Index(name = "idx_academic_program_leadr_mbr_id", columnList = "leadr_mbr_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AcademicProgramEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "academic_program_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false, updatable = false)
    private EventEntity event;

    /** 스터디/프로젝트 구분(#130 코드테이블). 승인 이후에는 바꿀 수 없다(S2에서 거부) */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academic_program_type_cd", nullable = false)
    private AcademicProgramTypeEntity type;

    @Enumerated(EnumType.STRING)
    @Column(name = "academic_program_stts_cd", nullable = false, length = 30)
    private AcademicProgramStatus status;

    @Column(name = "goal_cn", nullable = false, columnDefinition = "TEXT")
    private String goalContent;

    @Column(name = "prep_cn", columnDefinition = "TEXT")
    private String prepContent;

    @Column(name = "schedule_txt", length = 100)
    private String scheduleText;

    @Column(name = "cpcty_min_cnt")
    private Integer capacityMinCount;

    @Column(name = "cpcty_max_cnt")
    private Integer capacityMaxCount;

    /** 기획안 제출자. 사후 변경 불가라 updatable = false로 잠근다 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "prpsr_mbr_id", nullable = false, updatable = false)
    private MemberEntity proposer;

    /*
     * 스터디장/팀장. 승인 시점에 prpsrMbrId로 채워지므로(#133) 생성 직후에는 항상 NULL이다.
     * 정적 권한 코드가 아니라 이 필드 본인 여부로 "이 활동 한정" 소유권을 판정한다
     * (학술관리_데이터모델.md §5).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "leadr_mbr_id")
    private MemberEntity leader;

    @CreatedDate
    @Column(name = "crt_dt", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "mdfcn_dt", nullable = false)
    private Instant updatedAt;

    /*
     * 생성 팩토리. 상태는 항상 PROPOSED이고 리더는 항상 NULL이다 — 승인 전에는 스터디장/팀장이
     * 확정되지 않는다(학술관리_데이터모델.md §4).
     *
     * 이 팩토리를 부르는 자리는 #131이 아니다 — 기획안 접수는 폼 도메인이 맡고, 폼 응답이
     * 승인될 때 서버가 Event·AcademicProgram·CurriculumItem을 만드는 이관(ssccops#148,
     * 2026-08-23 설계 변경)이 실제 호출부가 된다. #131은 이렇게 만들어진 행을 조회만 한다.
     * `PROPOSED` 고정과 `leader = null` 고정은 그 이관 설계가 다시 볼 여지가 있다.
     */
    public static AcademicProgramEntity create(
            EventEntity event,
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
                type,
                AcademicProgramStatus.PROPOSED,
                goalContent,
                prepContent,
                scheduleText,
                capacityMinCount,
                capacityMaxCount,
                proposer,
                null,
                null,
                null);
    }
}
