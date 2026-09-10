package org.sscc.ssccopsserver.domain.form.service;

import java.util.EnumSet;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.code.error.FormErrorCode;
import org.sscc.ssccopsserver.domain.form.dto.PublicFormMetaResponse;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

/*
 * 익명 폼 메타 조회의 구현 (ssccops#201).
 *
 * "접수를 연 적 있다"는 상태로 판정한다. 전이표(FormStatusAction)가 DRAFT → OPEN → CLOSED →
 * OPEN만 허용하고 DRAFT에서 CLOSED로 가는 길이 없으므로, **DRAFT가 아닌 것과 연 적 있는 것은
 * 같은 집합이다.** 접수 일시(rcpt_bgng_dt·rcpt_end_dt)는 판정 재료가 아니다 — DRAFT에도
 * 기간을 미리 적어 둘 수 있고, 기간 없이 여는 폼도 정상이라 그 값으로는 갈리지 않는다.
 *
 * 조건을 "지금 접수 중"이 아니라 "연 적 있는"으로 두는 이유는 마감된 폼의 링크도 이미 방에
 * 뿌려져 있기 때문이다 — 그 카드가 마감과 함께 깨지면 안 된다.
 *
 * PublicEventServiceImpl과 같은 태도로 상태를 질의 조건에 넣는다. 조회한 뒤 상태를 보고
 * 거르면 그 분기 하나가 빠지는 것으로 작성 중인 폼의 제목이 익명에게 나간다.
 *
 * **지워진 폼(del_dt)도 404다** (#329). 상태와 함께 질의 조건에 넣으며 DRAFT와 같은 자리다 —
 * 없는 폼과 코드를 나누면 그 번호의 폼이 있었다가 지워졌다는 사실이 익명에게 새어 나가고,
 * 이 경로는 링크만 가진 크롤러가 부르는 자리라 그것이 곧 전부다.
 *
 * **메신저가 카드를 한 번 캐싱하면 갱신하지 않는다는 사실이 이 판단을 떠받친다**(ssccops#194).
 * 삭제를 다른 안내로 답하면 그 안내가 그대로 굳어, 되살린 뒤에도 "삭제된 폼"이라 말하는 카드가
 * 방에 남는다. 404는 카드를 만들지 않으므로 되살리기가 그대로 복구가 된다.
 *
 * 쓰기는 없다(@Transactional(readOnly = true)). 익명 경로에 쓰기 자리를 두지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublicFormMetaServiceImpl implements PublicFormMetaService {

    /** 접수를 연 적 있는 상태 — DRAFT를 뺀 전부 */
    private static final Set<FormStatus> EVER_OPENED =
            EnumSet.of(FormStatus.OPEN, FormStatus.CLOSED);

    private final FormRepository formRepository;

    @Override
    public PublicFormMetaResponse getFormMeta(Long formId) {
        return formRepository
                .findByIdAndDeletedAtIsNullAndStatusIn(formId, EVER_OPENED)
                .map(PublicFormMetaResponse::of)
                .orElseThrow(() -> new GeneralException(FormErrorCode.FORM_NOT_FOUND));
    }
}
