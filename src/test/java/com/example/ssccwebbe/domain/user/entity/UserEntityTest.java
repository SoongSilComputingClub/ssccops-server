package com.example.ssccwebbe.domain.user.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import com.example.ssccwebbe.domain.user.dto.UserRequestDto;
import com.example.ssccwebbe.global.security.UserRoleType;

@DataJpaTest
@ActiveProfiles("test")
class UserEntityTest {

    @Autowired private TestEntityManager entityManager;

    @Test
    @DisplayName("Builder를 사용하여 UserEntity를 생성할 수 있다")
    void builder_CreatesEntity_Success() {
        // given & when
        UserEntity entity =
                UserEntity.builder()
                        .username("testuser@test.com")
                        .isLock(false)
                        .isSocial(false)
                        .isAccepted(false)
                        .roleType(UserRoleType.PREUSER)
                        .nickname("Test User")
                        .email("testuser@test.com")
                        .build();

        // then
        assertThat(entity).isNotNull();
        assertThat(entity.getUsername()).isEqualTo("testuser@test.com");
        assertThat(entity.getIsLock()).isFalse();
        assertThat(entity.getIsSocial()).isFalse();
        assertThat(entity.getIsAccepted()).isFalse();
        assertThat(entity.getRoleType()).isEqualTo(UserRoleType.PREUSER);
        assertThat(entity.getNickname()).isEqualTo("Test User");
        assertThat(entity.getEmail()).isEqualTo("testuser@test.com");
    }

    @Test
    @DisplayName("소셜 로그인 사용자 엔티티를 생성할 수 있다")
    void builder_CreatesSocialUser_Success() {
        // given & when
        UserEntity entity =
                UserEntity.builder()
                        .username("GOOGLE_123456789")
                        .isLock(false)
                        .isSocial(true)
                        .socialProviderType(SocialProviderType.GOOGLE)
                        .roleType(UserRoleType.USER)
                        .nickname("Social User")
                        .email("socialuser@gmail.com")
                        .build();

        // then
        assertThat(entity).isNotNull();
        assertThat(entity.getUsername()).isEqualTo("GOOGLE_123456789");
        assertThat(entity.getIsSocial()).isTrue();
        assertThat(entity.getSocialProviderType()).isEqualTo(SocialProviderType.GOOGLE);
        assertThat(entity.getRoleType()).isEqualTo(UserRoleType.USER);
    }

    @Test
    @DisplayName("updateUser를 호출하면 email과 nickname이 업데이트된다")
    void updateUser_UpdatesEmailAndNickname() {
        // given
        UserEntity entity =
                UserEntity.builder()
                        .username("user@test.com")
                        .isLock(false)
                        .isSocial(false)
                        .roleType(UserRoleType.USER)
                        .nickname("Old Nickname")
                        .email("old@test.com")
                        .build();

        UserRequestDto dto = new UserRequestDto();
        dto.setEmail("new@test.com");
        dto.setNickname("New Nickname");

        // when
        entity.updateUser(dto);

        // then
        assertThat(entity.getEmail()).isEqualTo("new@test.com");
        assertThat(entity.getNickname()).isEqualTo("New Nickname");
    }

    @Test
    @DisplayName("updateUser를 호출해도 username과 roleType은 변경되지 않는다")
    void updateUser_DoesNotChangeUsernameAndRole() {
        // given
        String originalUsername = "user@test.com";
        UserRoleType originalRole = UserRoleType.USER;

        UserEntity entity =
                UserEntity.builder()
                        .username(originalUsername)
                        .isLock(false)
                        .isSocial(false)
                        .roleType(originalRole)
                        .nickname("Old Nickname")
                        .email("old@test.com")
                        .build();

        UserRequestDto dto = new UserRequestDto();
        dto.setEmail("new@test.com");
        dto.setNickname("New Nickname");
        dto.setUsername("hacker@test.com"); // DTO에 username이 있어도 무시됨

        // when
        entity.updateUser(dto);

        // then
        assertThat(entity.getUsername()).isEqualTo(originalUsername); // 변경되지 않음
        assertThat(entity.getRoleType()).isEqualTo(originalRole); // 변경되지 않음
    }

    @Test
    @DisplayName("엔티티를 저장하고 조회할 수 있다")
    void save_AndFind_Success() {
        // given
        UserEntity entity =
                UserEntity.builder()
                        .username("savetest@test.com")
                        .isLock(false)
                        .isSocial(false)
                        .roleType(UserRoleType.PREUSER)
                        .nickname("Save Test")
                        .email("savetest@test.com")
                        .build();

        // when
        UserEntity savedEntity = entityManager.persistAndFlush(entity);
        entityManager.clear(); // 캐시 클리어
        UserEntity foundEntity = entityManager.find(UserEntity.class, savedEntity.getId());

        // then
        assertThat(foundEntity).isNotNull();
        assertThat(foundEntity.getId()).isEqualTo(savedEntity.getId());
        assertThat(foundEntity.getUsername()).isEqualTo("savetest@test.com");
        assertThat(foundEntity.getNickname()).isEqualTo("Save Test");
    }

    @Test
    @DisplayName("엔티티를 수정하고 다시 저장할 수 있다")
    void update_AndSave_Success() {
        // given
        UserEntity entity =
                UserEntity.builder()
                        .username("updatetest@test.com")
                        .isLock(false)
                        .isSocial(false)
                        .roleType(UserRoleType.PREUSER)
                        .nickname("Update Test")
                        .email("updatetest@test.com")
                        .build();

        UserEntity savedEntity = entityManager.persistAndFlush(entity);
        entityManager.clear();

        // when
        UserEntity foundEntity = entityManager.find(UserEntity.class, savedEntity.getId());

        UserRequestDto dto = new UserRequestDto();
        dto.setEmail("updated@test.com");
        dto.setNickname("Updated Nickname");

        foundEntity.updateUser(dto);
        UserEntity updatedEntity = entityManager.persistAndFlush(foundEntity);
        entityManager.clear();

        UserEntity reFoundEntity = entityManager.find(UserEntity.class, updatedEntity.getId());

        // then
        assertThat(reFoundEntity.getEmail()).isEqualTo("updated@test.com");
        assertThat(reFoundEntity.getNickname()).isEqualTo("Updated Nickname");
    }

    @Test
    @DisplayName("ADMIN 권한 사용자를 생성할 수 있다")
    void builder_CreatesAdminUser_Success() {
        // given & when
        UserEntity entity =
                UserEntity.builder()
                        .username("admin@test.com")
                        .isLock(false)
                        .isSocial(false)
                        .roleType(UserRoleType.ADMIN)
                        .nickname("Admin User")
                        .email("admin@test.com")
                        .build();

        // then
        assertThat(entity.getRoleType()).isEqualTo(UserRoleType.ADMIN);
    }

    @Test
    @DisplayName("계정 잠김 상태의 사용자를 생성할 수 있다")
    void builder_CreatesLockedUser_Success() {
        // given & when
        UserEntity entity =
                UserEntity.builder()
                        .username("locked@test.com")
                        .isLock(true)
                        .isSocial(false)
                        .roleType(UserRoleType.USER)
                        .nickname("Locked User")
                        .email("locked@test.com")
                        .build();

        // then
        assertThat(entity.getIsLock()).isTrue();
    }

    @Test
    @DisplayName("합격 정보를 포함한 사용자를 생성할 수 있다")
    void builder_CreatesAcceptedUser_Success() {
        // given & when
        UserEntity entity =
                UserEntity.builder()
                        .username("accepted@test.com")
                        .isLock(false)
                        .isSocial(false)
                        .isAccepted(true)
                        .roleType(UserRoleType.USER)
                        .nickname("Accepted User")
                        .email("accepted@test.com")
                        .build();

        // then
        assertThat(entity.getIsAccepted()).isTrue();
    }

    @Test
    @DisplayName("socialProviderType이 null인 일반 사용자를 생성할 수 있다")
    void builder_CreatesUserWithNullSocialProvider_Success() {
        // given & when
        UserEntity entity =
                UserEntity.builder()
                        .username("regular@test.com")
                        .isLock(false)
                        .isSocial(false)
                        .socialProviderType(null)
                        .roleType(UserRoleType.PREUSER)
                        .nickname("Regular User")
                        .email("regular@test.com")
                        .build();

        // then
        assertThat(entity.getSocialProviderType()).isNull();
        assertThat(entity.getIsSocial()).isFalse();
    }
}
