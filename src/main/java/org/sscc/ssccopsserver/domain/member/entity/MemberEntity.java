package org.sscc.ssccopsserver.domain.member.entity;

import java.time.Instant;
import java.time.LocalDate;

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
import jakarta.persistence.UniqueConstraint;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "mbr",
        uniqueConstraints =
                @UniqueConstraint(name = "uk_mbr_student_number", columnNames = "stdnt_no"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class MemberEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "mbr_id")
    private Long id;

    @Column(name = "stdnt_no", nullable = false, updatable = false, length = 20)
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

    @Column(name = "join_ymd", nullable = false)
    private LocalDate joinDate;

    @CreatedDate
    @Column(name = "crt_dt", updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "mdfcn_dt")
    private Instant updatedAt;

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
            LocalDate joinDate) {
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
                joinDate,
                null,
                null);
    }

    public void updateBasicInfo(
            Integer generationNumber,
            String name,
            String departmentName,
            Integer academicYear,
            String phoneNumber,
            String email) {
        this.generationNumber = generationNumber;
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
}
