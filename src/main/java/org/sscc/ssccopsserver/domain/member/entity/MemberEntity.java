package org.sscc.ssccopsserver.domain.member.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.domain.AfterDomainEventPublication;
import org.springframework.data.domain.DomainEvents;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.sscc.ssccopsserver.domain.member.event.MemberCreatedEvent;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "mbr",
        uniqueConstraints = {
            @UniqueConstraint(name = "uk_mbr_student_number", columnNames = "stdnt_no"),
            @UniqueConstraint(name = "uk_mbr_auth_user_id", columnNames = "auth_user_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class MemberEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "mbr_id")
    private Long id;

    /*
     * 졸업 회원은 학번 없이 가입할 수 있어야 해 nullable이다 (#21). 학번이 기억나지 않는 졸업생을
     * 위해 가입 화면이 선택 입력으로 두고 있는데, NOT NULL이면 그 화면이 성립하지 않는다.
     *
     * uk_mbr_student_number는 그대로 유지한다 — NULL은 UNIQUE 제약에 걸리지 않기 때문이다.
     * 다만 학번 미입력을 빈 문자열로 저장하면 두 번째 졸업 회원부터 UNIQUE 충돌이 나므로
     * 반드시 NULL로 저장해야 한다.
     */
    @Column(name = "stdnt_no", updatable = false, length = 20)
    private String studentNumber;

    @Column(name = "gen_no", nullable = false)
    private Integer generationNumber;

    @Column(name = "mbr_nm", nullable = false, length = 50)
    private String name;

    @Column(name = "scsbjt_nm", length = 100)
    private String departmentName;

    @Column(name = "scyr_no")
    private Integer academicYear;

    @Column(name = "telno", length = 20)
    private String phoneNumber;

    @Column(name = "eml", length = 255)
    private String email;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mbr_grd_cd", nullable = false)
    private MemberGradeEntity membershipGrade;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mbr_stts_cd", nullable = false)
    private MemberStatusEntity membershipStatus;

    /*
     * 전산 가입일 — 이 사람이 SSCC 전산 시스템에 계정을 만든 날이다. 가입 API는
     * LocalDate.now(clock)로, CSV 이관은 이관을 실행한 날로 채운다.
     *
     * 이름에 sys_를 붙인 것은 join_ymd 한 컬럼이 '전산 가입일'과 '동아리 입부일' 두 뜻으로
     * 읽혀 왔기 때문이다 (#204). 데이터사전 설명은 'SSCC 최초 가입일'인데 이관 회원만 명부에
     * 적힌 입부일을 담고 있어, 행만 봐서는 어느 쪽인지 구별할 수 없었다.
     *
     * **자바 필드명도 함께 바꾼다.** 컬럼만 고치면 코드에서는 여전히 joinDate로 읽혀 이름이
     * 다시 갈리는데, 그 모호함을 없애는 것이 이 개명의 목적이다.
     */
    @Column(name = "sys_join_ymd", nullable = false)
    private LocalDate systemJoinDate;

    /*
     * 동아리 가입 시기 (#204). 이 사람이 SSCC에 입부한 연·월이며, 전산 가입일과 달리 명부가
     * 아는 사실이다. 기수 산출의 근거이기도 하다 (기수 = 동아리 가입 연도 − 1982).
     *
     * **DATE 한 컬럼이 아니라 연·월 두 컬럼이다.** 날짜로 받고 일(日)을 1일로 고정하는 안을
     * 검토했다가 버렸다 — 모르는 값을 만들어 내는 것이고, 화면·엑셀에서 '3월 1일 입부'로 읽히며
     * 나중에 진짜 1일 입부와 구별되지 않는다. 선배들이 며칠에 입부했는지 아무도 모른다는 것이
     * 이 컬럼이 생긴 이유이므로, 모르는 것을 모른다고 둘 수 있는 모양이어야 한다.
     *
     * **둘 다 NULL 허용이다.** 기존 join_ymd 값을 백필하지 않기로 했기 때문이다(ssccops#155) —
     * 두 뜻이 섞여 있어 옮기면 불확실한 값이 곧 기수의 근거가 된다. 넣을 값이 없으니 NOT NULL은
     * 애초에 성립하지 않고, 월만 비는 경우(연도만 아는 명부)도 그대로 담긴다.
     */
    @Column(name = "clb_join_yr_no")
    private Integer clubJoinYear;

    @Column(name = "clb_join_mm_no")
    private Integer clubJoinMonth;

    // Supabase Auth 사용자 식별자(auth.users.id). 아직 로그인하지 않은 이관 회원은 NULL
    @Column(name = "auth_user_id")
    private UUID authUserId;

    @CreatedDate
    @Column(name = "crt_dt", updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "mdfcn_dt")
    private Instant updatedAt;

    /*
     * 이번 저장이 '새로 만든 회원'인지 표시하는 표식이다 (#184). 컬럼이 아니라 @Transient이며,
     * 저장 직후 Spring Data가 읽어 가는 domainEvents()가 이 값으로 이벤트를 낼지 정한다.
     *
     * 필드로 두고 create()가 생성자 인자로 넘기는 이유는 두 가지다. 첫째, 이벤트 객체를 여기
     * 담아 두지 않는다 — mbr_id는 IDENTITY라 create() 시점에는 아직 정해지지 않아, 그때 만든
     * 이벤트에는 null이 실린다. 표식만 세워 두고 **id가 정해진 뒤에** 이벤트를 만든다.
     * 둘째, @Getter(NONE)으로 접근자를 막는다 — 이 값은 저장 경로의 내부 사정이지 회원의
     * 속성이 아니고, 노출되면 DTO 매핑이 이것까지 실어 나른다.
     */
    @Transient
    @Getter(AccessLevel.NONE)
    private boolean newlyCreated;

    /*
     * 저장 직후 Spring Data JPA가 읽어 가는 자리 (#184). save·saveAll·saveAndFlush 어느 쪽으로
     * 저장해도 불린다 — 발행 여부를 가리는 판정이 메서드 이름이 "save"로 시작하는가이기 때문에,
     * 명부 이관이 쓰는 saveAndFlush도 함께 잡힌다.
     *
     * 갱신 저장에는 아무것도 내지 않는다. Hibernate가 조회로 되살린 엔티티는 기본 생성자를
     * 지나므로 newlyCreated가 false이고, 그래서 updateBasicInfo 뒤의 저장은 조용하다.
     */
    @DomainEvents
    Collection<Object> domainEvents() {
        return newlyCreated ? List.of(new MemberCreatedEvent(id)) : List.of();
    }

    /*
     * 발행이 끝나면 표식을 내린다. 같은 엔티티로 저장이 한 번 더 일어나도 이벤트가 두 번
     * 나가지 않아야 한다 — 듣는 쪽(시드)이 멱등하긴 하지만, 멱등성을 발행 횟수의 변명으로
     * 쓰기 시작하면 나중에 멱등하지 않은 리스너가 붙는 순간 조용히 깨진다.
     */
    @AfterDomainEventPublication
    void clearDomainEvents() {
        this.newlyCreated = false;
    }

    public static MemberEntity create(
            String studentNumber,
            Integer generationNumber,
            String name,
            String departmentName,
            Integer academicYear,
            String phoneNumber,
            String email,
            MemberGradeEntity membershipGrade,
            MemberStatusEntity membershipStatus,
            LocalDate systemJoinDate,
            Integer clubJoinYear,
            Integer clubJoinMonth) {
        return new MemberEntity(
                null,
                studentNumber,
                generationNumber,
                name,
                departmentName,
                academicYear,
                phoneNumber,
                email,
                membershipGrade,
                membershipStatus,
                systemJoinDate,
                clubJoinYear,
                clubJoinMonth,
                null,
                null,
                null,
                // 저장되면 MemberCreatedEvent를 낸다 (#184). 여기가 회원이 생기는 유일한 자리다
                true);
    }

    public void updateBasicInfo(
            Integer generationNumber,
            Integer clubJoinYear,
            Integer clubJoinMonth,
            String name,
            String departmentName,
            Integer academicYear,
            String phoneNumber,
            String email) {
        this.generationNumber = generationNumber;
        this.clubJoinYear = clubJoinYear;
        this.clubJoinMonth = clubJoinMonth;
        this.name = name;
        this.departmentName = departmentName;
        this.academicYear = academicYear;
        this.phoneNumber = phoneNumber;
        this.email = email;
    }

    public void changeMembershipGrade(MemberGradeEntity membershipGrade) {
        this.membershipGrade = membershipGrade;
    }

    public void changeMembershipStatus(MemberStatusEntity membershipStatus) {
        this.membershipStatus = membershipStatus;
    }

    public void assignAuthUserId(UUID authUserId) {
        this.authUserId = authUserId;
    }
}
