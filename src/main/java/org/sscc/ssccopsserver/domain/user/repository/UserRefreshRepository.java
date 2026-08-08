package org.sscc.ssccopsserver.domain.user.repository;

import java.time.LocalDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.user.entity.UserRefreshEntity;

public interface UserRefreshRepository extends JpaRepository<UserRefreshEntity, Long> {
    Boolean existsByRefresh(String refreshToken);

    void deleteByRefresh(String refresh);

    void deleteByUsername(String username);

    void deleteByCreatedDateBefore(LocalDateTime createdDateBefore);
}
