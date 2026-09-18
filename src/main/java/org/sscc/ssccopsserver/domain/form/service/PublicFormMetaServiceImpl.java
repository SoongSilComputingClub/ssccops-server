package org.sscc.ssccopsserver.domain.form.service;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.code.error.FormErrorCode;
import org.sscc.ssccopsserver.domain.form.dto.PublicFormMetaResponse;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
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
 *
 * **경로 변수가 두 모양이고 허용 조건이 다르다** (ADR-0036 · ssccops#359). 무작위 키(UUID)는
 * 위 규칙 그대로 «연 적 있는 폼»이 열린다. 예전 숫자 id는 **지금 접수 중(OPEN)인 폼만** 연다 —
 * 숫자는 1부터 훑을 수 있으므로, 훑어서 얻는 것이 «지금 링크가 돌고 있는 폼의 제목»을 넘지
 * 않게 한다. 마감된 폼의 옛 숫자 링크는 카드가 기본 문구로 떨어지는데, 그 폼은 새 링크(키)로
 * 다시 뿌리면 된다. 숫자를 아예 닫지 않은 것은 이미 뿌린 링크를 살리기로 한 운영진 조건이고,
 * 닫는 시점은 ADR-0036 «폐기 조건»이 든다. FormRefResolver를 쓰지 않는 것은 그쪽이 «어느
 * 모양이든 같은 폼»으로 푸는 자리라 여기의 조건 차이를 담지 못하기 때문이다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublicFormMetaServiceImpl implements PublicFormMetaService {

    /** 접수를 연 적 있는 상태 — DRAFT를 뺀 전부 */
    private static final Set<FormStatus> EVER_OPENED =
            EnumSet.of(FormStatus.OPEN, FormStatus.CLOSED);

    private final FormRepository formRepository;

    /** 숫자 id로 열리는 상태 — 지금 접수 중인 것뿐 */
    private static final Set<FormStatus> NOW_OPEN = EnumSet.of(FormStatus.OPEN);

    @Override
    public PublicFormMetaResponse getFormMeta(String formRef) {
        Optional<UUID> key = FormRefResolver.asKey(formRef);
        Optional<FormEntity> form =
                key.isPresent()
                        ? formRepository.findByFormKeyAndDeletedAtIsNullAndStatusIn(
                                key.get(), EVER_OPENED)
                        : FormRefResolver.asId(formRef)
                                .flatMap(
                                        id ->
                                                formRepository
                                                        .findByIdAndDeletedAtIsNullAndStatusIn(
                                                                id, NOW_OPEN));
        return form.map(PublicFormMetaResponse::of)
                .orElseThrow(() -> new GeneralException(FormErrorCode.FORM_NOT_FOUND));
    }
}
