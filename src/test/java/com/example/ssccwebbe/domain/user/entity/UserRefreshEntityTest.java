package com.example.ssccwebbe.domain.user.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
class UserRefreshEntityTest {

    @Autowired private TestEntityManager entityManager;

    @Test
    @DisplayName("Builder를 사용하여 UserRefreshEntity를 생성할 수 있다")
    void builder_CreatesEntity_Success() {
        // given & when
        UserRefreshEntity entity =
                UserRefreshEntity.builder()
                        .username("user@test.com")
                        .refresh("test-refresh-token-12345")
                        .build();

        // then
        assertThat(entity).isNotNull();
        assertThat(entity.getUsername()).isEqualTo("user@test.com");
        assertThat(entity.getRefresh()).isEqualTo("test-refresh-token-12345");
    }

    @Test
    @DisplayName("엔티티를 저장하고 조회할 수 있다")
    void save_AndFind_Success() {
        // given
        UserRefreshEntity entity =
                UserRefreshEntity.builder()
                        .username("savetest@test.com")
                        .refresh("save-test-refresh-token")
                        .build();

        // when
        UserRefreshEntity savedEntity = entityManager.persistAndFlush(entity);
        entityManager.clear(); // 캐시 클리어
        UserRefreshEntity foundEntity =
                entityManager.find(UserRefreshEntity.class, savedEntity.getId());

        // then
        assertThat(foundEntity).isNotNull();
        assertThat(foundEntity.getId()).isEqualTo(savedEntity.getId());
        assertThat(foundEntity.getUsername()).isEqualTo("savetest@test.com");
        assertThat(foundEntity.getRefresh()).isEqualTo("save-test-refresh-token");
    }

    @Test
    @DisplayName("같은 username에 대해 여러 refresh token을 저장할 수 있다")
    void save_MultipleTokensSameUser_Success() {
        // given
        String username = "user@test.com";

        UserRefreshEntity token1 =
                UserRefreshEntity.builder().username(username).refresh("token-1").build();

        UserRefreshEntity token2 =
                UserRefreshEntity.builder().username(username).refresh("token-2").build();

        // when
        UserRefreshEntity savedToken1 = entityManager.persistAndFlush(token1);
        UserRefreshEntity savedToken2 = entityManager.persistAndFlush(token2);

        // then
        assertThat(savedToken1.getId()).isNotNull();
        assertThat(savedToken2.getId()).isNotNull();
        assertThat(savedToken1.getId()).isNotEqualTo(savedToken2.getId());
        assertThat(savedToken1.getUsername()).isEqualTo(username);
        assertThat(savedToken2.getUsername()).isEqualTo(username);
    }

    @Test
    @DisplayName("여러 사용자의 refresh token을 저장할 수 있다")
    void save_MultipleUsersTokens_Success() {
        // given
        UserRefreshEntity user1Token =
                UserRefreshEntity.builder()
                        .username("user1@test.com")
                        .refresh("user1-refresh-token")
                        .build();

        UserRefreshEntity user2Token =
                UserRefreshEntity.builder()
                        .username("user2@test.com")
                        .refresh("user2-refresh-token")
                        .build();

        // when
        UserRefreshEntity savedUser1Token = entityManager.persistAndFlush(user1Token);
        UserRefreshEntity savedUser2Token = entityManager.persistAndFlush(user2Token);

        // then
        assertThat(savedUser1Token.getId()).isNotNull();
        assertThat(savedUser2Token.getId()).isNotNull();
        assertThat(savedUser1Token.getUsername()).isEqualTo("user1@test.com");
        assertThat(savedUser2Token.getUsername()).isEqualTo("user2@test.com");
    }

    @Test
    @DisplayName("긴 refresh token을 저장할 수 있다 (512자 제한)")
    void save_LongRefreshToken_Success() {
        // given
        String longToken = "a".repeat(500); // 512자 제한 내
        UserRefreshEntity entity =
                UserRefreshEntity.builder().username("user@test.com").refresh(longToken).build();

        // when
        UserRefreshEntity savedEntity = entityManager.persistAndFlush(entity);
        entityManager.clear();
        UserRefreshEntity foundEntity =
                entityManager.find(UserRefreshEntity.class, savedEntity.getId());

        // then
        assertThat(foundEntity).isNotNull();
        assertThat(foundEntity.getRefresh()).isEqualTo(longToken);
        assertThat(foundEntity.getRefresh().length()).isEqualTo(500);
    }

    @Test
    @DisplayName("소셜 로그인 사용자의 refresh token을 저장할 수 있다")
    void save_SocialUserToken_Success() {
        // given
        UserRefreshEntity entity =
                UserRefreshEntity.builder()
                        .username("GOOGLE_123456789")
                        .refresh("google-user-refresh-token")
                        .build();

        // when
        UserRefreshEntity savedEntity = entityManager.persistAndFlush(entity);

        // then
        assertThat(savedEntity.getId()).isNotNull();
        assertThat(savedEntity.getUsername()).isEqualTo("GOOGLE_123456789");
        assertThat(savedEntity.getRefresh()).isEqualTo("google-user-refresh-token");
    }

    @Test
    @DisplayName("저장된 엔티티를 삭제할 수 있다")
    void delete_SavedEntity_Success() {
        // given
        UserRefreshEntity entity =
                UserRefreshEntity.builder()
                        .username("user@test.com")
                        .refresh("delete-test-token")
                        .build();

        UserRefreshEntity savedEntity = entityManager.persistAndFlush(entity);
        Long savedId = savedEntity.getId();

        // when
        entityManager.remove(savedEntity);
        entityManager.flush();
        entityManager.clear();

        // then
        UserRefreshEntity deletedEntity = entityManager.find(UserRefreshEntity.class, savedId);
        assertThat(deletedEntity).isNull();
    }

    @Test
    @DisplayName("저장된 엔티티를 조회하고 값을 확인할 수 있다")
    void find_CheckValues_Success() {
        // given
        String username = "testuser@test.com";
        String refreshToken = "test-refresh-token-abc123";

        UserRefreshEntity entity =
                UserRefreshEntity.builder().username(username).refresh(refreshToken).build();

        // when
        UserRefreshEntity savedEntity = entityManager.persistAndFlush(entity);
        entityManager.clear();
        UserRefreshEntity foundEntity =
                entityManager.find(UserRefreshEntity.class, savedEntity.getId());

        // then
        assertThat(foundEntity).isNotNull();
        assertThat(foundEntity.getId()).isNotNull();
        assertThat(foundEntity.getUsername()).isEqualTo(username);
        assertThat(foundEntity.getRefresh()).isEqualTo(refreshToken);
    }

    @Test
    @DisplayName("ID가 자동으로 생성된다")
    void save_GeneratesId_Success() {
        // given
        UserRefreshEntity entity =
                UserRefreshEntity.builder()
                        .username("user@test.com")
                        .refresh("auto-id-test-token")
                        .build();

        // when
        assertThat(entity.getId()).isNull(); // 저장 전에는 null
        UserRefreshEntity savedEntity = entityManager.persistAndFlush(entity);

        // then
        assertThat(savedEntity.getId()).isNotNull(); // 저장 후에는 자동 생성
        assertThat(savedEntity.getId()).isGreaterThan(0L);
    }
}
