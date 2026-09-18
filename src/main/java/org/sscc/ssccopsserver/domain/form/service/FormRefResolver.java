package org.sscc.ssccopsserver.domain.form.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.form.code.error.FormErrorCode;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

/*
 * 공개 폼 경로의 식별자 판별 (ADR-0036 · ssccops#359).
 *
 * `/v1/forms/{formId}/…`의 경로 변수는 두 모양을 받는다 — 무작위 키(UUID)와 예전 숫자 id.
 * 숫자를 계속 받는 것은 이미 뿌린 링크를 살리기로 한 운영진 조건 때문이고, 새 링크는 웹이
 * 키로만 만든다. 판별은 한 곳에서만 한다: UUID로 읽히면 키, 아니면 정수, 둘 다 아니면 404 —
 * "없는 폼"과 "잘못된 주소"를 나누면 어느 값이 유효한 모양인지가 새어 나간다(익명 meta와 같은
 * 태도).
 *
 * 키로 찾은 뒤 **id로 바꿔 돌려주는** 이유는 뒤의 서비스(FormResponseService 등)가 전부 id를
 * 받기 때문이다. 키를 서비스까지 흘리면 조회 메서드가 두 벌이 된다. 지워진 폼(del_dt)은 여기서
 * 거르지 않는다 — id 경로가 지금 그것을 어떻게 다루는지(FORM_NOT_FOUND) 그대로 따르게 한다.
 *
 * 익명 meta는 이 컴포넌트를 쓰지 않는다. 그쪽은 키와 정수의 **허용 조건이 다르므로**(정수는
 * 접수 중인 폼만) PublicFormMetaServiceImpl이 따로 판별한다.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FormRefResolver {

    private final FormRepository formRepository;

    /** UUID로 읽히면 그 키. 아니면 empty — 정수인지는 호출부가 본다. */
    public static Optional<UUID> asKey(String ref) {
        if (ref == null || ref.length() != 36) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(ref));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** 정수로 읽히면 그 값. UUID도 정수도 아니면 empty. */
    public static Optional<Long> asId(String ref) {
        if (ref == null || ref.isEmpty() || ref.length() > 18) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(ref));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** 경로 변수 → form_id. 키면 조회해서, 정수면 그대로. 어느 쪽도 아니면 FORM_NOT_FOUND. */
    public Long resolveId(String ref) {
        Optional<UUID> key = asKey(ref);
        if (key.isPresent()) {
            return formRepository
                    .findByFormKey(key.get())
                    .map(FormEntity::getId)
                    .orElseThrow(() -> new GeneralException(FormErrorCode.FORM_NOT_FOUND));
        }
        return asId(ref).orElseThrow(() -> new GeneralException(FormErrorCode.FORM_NOT_FOUND));
    }
}
