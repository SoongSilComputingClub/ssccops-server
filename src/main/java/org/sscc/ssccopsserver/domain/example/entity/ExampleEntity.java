package org.sscc.ssccopsserver.domain.example.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * 새 도메인을 추가할 때 참고하는 템플릿 엔티티.
 * protected 기본생성자 + private 전체생성자 + 정적 팩토리(create)는 MemberEntity에서 채택한
 * 컨벤션이다 (구 UserEntity가 쓰던 @Builder 방식 대신, 생성 시점에 필요한 값만 받도록 강제).
 */
@Entity
@EntityListeners(AuditingEntityListener.class) // 생성일, 수정일 자동 변경
@Table(name = "example_entity")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ExampleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(length = 2000)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExampleStatus status;

    @CreatedDate
    @Column(name = "created_date", updatable = false)
    private LocalDateTime createdDate;

    @LastModifiedDate
    @Column(name = "updated_date")
    private LocalDateTime updatedDate;

    public static ExampleEntity create(String title, String content) {
        return new ExampleEntity(null, title, content, ExampleStatus.ACTIVE, null, null);
    }

    public void update(String title, String content) {
        this.title = title;
        this.content = content;
    }

    // 소프트 delete
    public void softDelete() {
        this.status = ExampleStatus.DELETED;
    }

    public boolean isDeleted() {
        return ExampleStatus.DELETED.equals(this.status);
    }
}
