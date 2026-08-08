package com.example.ssccwebbe.domain.user.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SocialProviderTypeTest {

    @Test
    @DisplayName("GOOGLE enum 값이 올바르게 정의되어 있다")
    void google_EnumValue_DefinedCorrectly() {
        // when
        SocialProviderType google = SocialProviderType.GOOGLE;

        // then
        assertThat(google).isNotNull();
        assertThat(google.name()).isEqualTo("GOOGLE");
        assertThat(google.getDescription()).isEqualTo("구글");
    }

    @Test
    @DisplayName("values() 메서드가 모든 enum 값을 반환한다")
    void values_ReturnsAllEnumValues() {
        // when
        SocialProviderType[] values = SocialProviderType.values();

        // then
        assertThat(values).isNotNull();
        assertThat(values).hasSize(1);
        assertThat(values).contains(SocialProviderType.GOOGLE);
    }

    @Test
    @DisplayName("valueOf() 메서드로 GOOGLE을 조회할 수 있다")
    void valueOf_WithGoogle_ReturnsGoogleEnum() {
        // when
        SocialProviderType result = SocialProviderType.valueOf("GOOGLE");

        // then
        assertThat(result).isNotNull();
        assertThat(result).isEqualTo(SocialProviderType.GOOGLE);
        assertThat(result.getDescription()).isEqualTo("구글");
    }

    @Test
    @DisplayName("valueOf() 메서드에 잘못된 값을 전달하면 IllegalArgumentException이 발생한다")
    void valueOf_WithInvalidValue_ThrowsException() {
        // when & then
        assertThatThrownBy(() -> SocialProviderType.valueOf("INVALID"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("valueOf() 메서드에 null을 전달하면 NullPointerException이 발생한다")
    void valueOf_WithNull_ThrowsException() {
        // when & then
        assertThatThrownBy(() -> SocialProviderType.valueOf(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("valueOf() 메서드는 대소문자를 구분한다")
    void valueOf_IsCaseSensitive() {
        // when & then
        assertThatThrownBy(() -> SocialProviderType.valueOf("google"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> SocialProviderType.valueOf("Google"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("getDescription() 메서드가 올바른 설명을 반환한다")
    void getDescription_ReturnsCorrectDescription() {
        // when
        String description = SocialProviderType.GOOGLE.getDescription();

        // then
        assertThat(description).isNotNull();
        assertThat(description).isEqualTo("구글");
    }

    @Test
    @DisplayName("같은 enum 값은 동일성(==)을 보장한다")
    void sameEnumValue_GuaranteesIdentity() {
        // when
        SocialProviderType google1 = SocialProviderType.GOOGLE;
        SocialProviderType google2 = SocialProviderType.valueOf("GOOGLE");

        // then
        assertThat(google1).isSameAs(google2);
        assertThat(google1 == google2).isTrue();
    }

    @Test
    @DisplayName("enum을 switch 문에서 사용할 수 있다")
    void enumValue_CanBeUsedInSwitch() {
        // given
        SocialProviderType provider = SocialProviderType.GOOGLE;
        String result;

        // when
        switch (provider) {
            case GOOGLE:
                result = "Google Login";
                break;
            default:
                result = "Unknown";
                break;
        }

        // then
        assertThat(result).isEqualTo("Google Login");
    }

    @Test
    @DisplayName("enum의 ordinal 값이 올바르다")
    void ordinal_ReturnsCorrectValue() {
        // when
        int ordinal = SocialProviderType.GOOGLE.ordinal();

        // then
        assertThat(ordinal).isZero();
    }

    @Test
    @DisplayName("enum의 toString()이 name()과 동일한 값을 반환한다")
    void toString_ReturnsSameAsName() {
        // when
        String toString = SocialProviderType.GOOGLE.toString();
        String name = SocialProviderType.GOOGLE.name();

        // then
        assertThat(toString).isEqualTo(name);
        assertThat(toString).isEqualTo("GOOGLE");
    }
}
