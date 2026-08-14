package org.sscc.ssccopsserver.global.config;

import java.util.Map;

import org.hibernate.cfg.AvailableSettings;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.format.FormatMapper;
import org.hibernate.type.format.jackson.JacksonJsonFormatMapper;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Configuration;
import org.sscc.ssccopsserver.domain.form.code.error.FormErrorCode;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
import org.sscc.ssccopsserver.domain.form.entity.ResponseContent;
import org.sscc.ssccopsserver.global.apipayload.code.error.ErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/*
 * JSON 컬럼(@JdbcTypeCode(SqlTypes.JSON))의 직렬화·역직렬화를 담당하는 Hibernate FormatMapper 등록.
 *
 * Hibernate는 클래스패스에 Jackson이 있으면 알아서 JacksonJsonFormatMapper를 쓰므로, 이 설정이
 * 없어도 JSONB 매핑 자체는 동작한다. 그럼에도 한 겹 감싸는 이유는 역직렬화 실패의 응답 때문이다.
 *
 * JSONB는 DB가 JSON 문법만 보장할 뿐 우리 구조까지 보장하지 않는다. 기준 코드 밖의 qitemTypeCd가
 * 섞이거나 손으로 고친 행이 들어오면 Jackson 예외가 그대로 올라가 전역 핸들러가 500으로 떨어뜨린다.
 * 500은 "서버가 고장났다"는 뜻이라 프론트가 할 수 있는 일이 없지만, 실제로 깨진 것은 그 폼 한 건의
 * 데이터다. 그래서 폼 도메인의 JSON 타입에 한해 도메인 오류(FormErrorCode)로 바꿔 내려보낸다.
 *
 * 폼이 아닌 타입은 손대지 않고 원래 예외를 그대로 다시 던진다 — 나중에 다른 도메인이 JSON 컬럼을
 * 쓸 때 이 설정이 남의 오류를 폼 오류라고 말하면 안 된다.
 *
 * JacksonJsonFormatMapper는 final이라 상속할 수 없어 위임으로 감싼다. Spring MVC가 쓰는
 * ObjectMapper를 넘기지 않고 Hibernate 기본 인스턴스를 그대로 쓰는 것도 의도된 것이다 —
 * API 응답 표기를 바꾸는 설정이 DB에 저장된 문서의 모양까지 바꾸면 안 된다.
 */
@Configuration
public class JsonFormatMapperConfig implements HibernatePropertiesCustomizer {

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(
                AvailableSettings.JSON_FORMAT_MAPPER,
                new DomainErrorTranslatingJsonFormatMapper(new JacksonJsonFormatMapper()));
    }

    static class DomainErrorTranslatingJsonFormatMapper implements FormatMapper {

        private final FormatMapper delegate;

        DomainErrorTranslatingJsonFormatMapper(FormatMapper delegate) {
            this.delegate = delegate;
        }

        @Override
        public <T> T fromString(
                CharSequence charSequence, JavaType<T> javaType, WrapperOptions wrapperOptions) {
            try {
                return delegate.fromString(charSequence, javaType, wrapperOptions);
            } catch (RuntimeException e) {
                throw translate(javaType, e);
            }
        }

        /*
         * 저장 방향도 같이 감싼다. 서비스가 조립한 객체를 Jackson이 쓰지 못하는 경우는 드물지만,
         * 그때 500으로 떨어지면 "저장에 실패했다"는 사실이 폼 오류가 아니라 서버 오류로 보인다.
         */
        @Override
        public <T> String toString(T value, JavaType<T> javaType, WrapperOptions wrapperOptions) {
            try {
                return delegate.toString(value, javaType, wrapperOptions);
            } catch (RuntimeException e) {
                throw translate(javaType, e);
            }
        }

        private RuntimeException translate(JavaType<?> javaType, RuntimeException cause) {
            ErrorCode errorCode = errorCodeOf(javaType);
            return errorCode == null ? cause : new GeneralException(errorCode);
        }

        private ErrorCode errorCodeOf(JavaType<?> javaType) {
            Class<?> type = javaType.getJavaTypeClass();
            if (QuestionCompositionContent.class.isAssignableFrom(type)) {
                return FormErrorCode.FORM_CONTENT_MALFORMED;
            }
            if (ResponseContent.class.isAssignableFrom(type)) {
                return FormErrorCode.RESPONSE_CONTENT_MALFORMED;
            }
            return null;
        }
    }
}
