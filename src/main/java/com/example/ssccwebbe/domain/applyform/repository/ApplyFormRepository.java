package com.example.ssccwebbe.domain.applyform.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.ssccwebbe.domain.applyform.entity.ApplyFormEntity;
import com.example.ssccwebbe.domain.applyform.entity.ApplyFormStatus;
import com.example.ssccwebbe.domain.applyform.entity.CodingExp;
import com.example.ssccwebbe.domain.applyform.entity.Gender;
import com.example.ssccwebbe.domain.user.entity.UserEntity;

// User 기준으로 본인의 지원서 1개를 조회하는 메서드를 제공
// -> 조회 이유는 이미 작성한 지원서가 있으면 그거 내용을 그대로 불러와서 적용시키기 위함

public interface ApplyFormRepository extends JpaRepository<ApplyFormEntity, Long> {
    Optional<ApplyFormEntity> findByUser(UserEntity user);

    boolean existsByUser(UserEntity user);

    long countByGenderAndStatusNot(Gender gender, ApplyFormStatus status);

    long countByCodingExpAndStatusNot(CodingExp codingExp, ApplyFormStatus status);

    long countByStatusNot(ApplyFormStatus status);

    List<ApplyFormEntity> findAllByStatusNot(ApplyFormStatus status);
}
