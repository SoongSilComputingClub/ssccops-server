package com.example.ssccwebbe.domain.user.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.ssccwebbe.domain.user.entity.UserEntity;

public interface UserRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByUsernameAndIsSocial(String username, Boolean social);

    Optional<UserEntity> findByUsernameAndIsLock(String username, Boolean isLock);
}
