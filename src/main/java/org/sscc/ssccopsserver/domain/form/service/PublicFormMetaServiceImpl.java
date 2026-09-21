package org.sscc.ssccopsserver.domain.form.service;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.form.code.DesignatableSystemForm;
import org.sscc.ssccopsserver.domain.form.code.FormReceiptStatus;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.code.error.FormErrorCode;
import org.sscc.ssccopsserver.domain.form.dto.PublicFormMetaResponse;
import org.sscc.ssccopsserver.domain.form.dto.PublicOpenFormResponse;
import org.sscc.ssccopsserver.domain.form.dto.PublicSystemFormMetaResponse;
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

    private final FormReceiptPolicy formReceiptPolicy;

    /** 숫자 id로 열리는 상태 — 지금 접수 중인 것뿐 */
    private static final Set<FormStatus> NOW_OPEN = EnumSet.of(FormStatus.OPEN);

    /** 익명 메타를 내주는 시스템 폼 코드 (#520). 지정 가능 목록과는 다른 축이다 — getSystemFormMeta 주석 */
    private static final Set<String> ANONYMOUS_SYSTEM_FORM_CODES =
            Set.of(DesignatableSystemForm.RECRUIT.code());

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

    /*
     * 접수 중인 폼 목록 (ssccops#381 · ADR-0038 «접수 중 폼: 제목·마감·폼 키»). «접수 중»의 판정은
     * FormReceiptPolicy.filterFor(ACCEPTING)가 만든 조건을 관리자 목록과 같은 질의에 태운 것이다 —
     * 상태·기간 규칙을 여기서 다시 쓰지 않는다(그 판정의 유일한 구현은 그 클래스다).
     *
     * **시스템 폼(기획안 · sys_yn)은 뺀다.** 그 폼은 부원이 lms에서 내는 것이라 익명 홈의 «지금
     * 지원할 수 있는 것»에 뜨면 로그인 벽에 부딪히는 링크가 된다. 그것 말고는 거르지 않는다 —
     * 폼에 «공개» 플래그가 따로 없고(form/AGENTS.md — 공개는 링크를 누구나 열 수 있다는 뜻) 접수를
     * 연 폼은 어차피 링크가 돌고 있는 폼이다. 마감이 가까운 것부터, 마감 없는 것은 맨 뒤다.
     */
    @Override
    public List<PublicOpenFormResponse> getOpenForms() {
        FormReceiptPolicy.ReceiptFilter accepting =
                formReceiptPolicy.filterFor(FormReceiptStatus.ACCEPTING);
        return formRepository
                .findAllForAdminList(
                        accepting.statuses(), null, accepting.periodMatch().name(), accepting.now())
                .stream()
                .filter(form -> !form.isSystemForm())
                .sorted(
                        Comparator.comparing(
                                FormEntity::getReceiptEndAt,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .map(PublicOpenFormResponse::of)
                .toList();
    }

    /*
     * 지정 시스템 폼의 익명 메타 (#520 · ssccops#436 · ADR-0044). www의 /join이 «지원하기» CTA를
     * 그리는 재료이며 신입회원 모집 폼(RECRUIT)만 연다.
     *
     * **익명에게 여는 코드를 여기서 한 번 더 좁힌다.** 지정할 수 있는 코드(DesignatableSystemForm)와
     * 익명에게 내주는 코드는 다른 축이다 — 지금은 둘 다 RECRUIT 하나지만, 운영진이 지정하는 폼이
     * 늘어도 익명 홈에 실을 폼은 따로 골라야 한다(기획안이 /forms/open에서 빠지는 것과 같은 이유:
     * 부원이 lms에서 내는 폼이 익명 페이지에 뜨면 로그인 벽에 부딪히는 링크가 된다). 그래서
     * PROPOSAL은 지정 목록에 없을 뿐 아니라 이 집합에도 없고, 어느 쪽이든 404다.
     *
     * 404를 한 코드로 묶는다: 허용 밖 코드 · 아직 지정되지 않음 · 지정됐지만 DRAFT. /forms/{id}/meta가
     * DRAFT와 없는 폼을 나누지 않는 것과 같은 이유이며, «연 적 있는» 판정(EVER_OPENED)도 그쪽과 같은
     * 집합을 질의 조건에 넣는다. www는 404를 «지금은 모집 기간이 아닙니다»로 그린다 — 마감(CLOSED ·
     * EXPIRED)은 200이고 receiptStatus가 말하므로 화면이 마감 안내와 «준비 중»을 가를 수 있다.
     */
    @Override
    public PublicSystemFormMetaResponse getSystemFormMeta(String systemFormCode) {
        if (!ANONYMOUS_SYSTEM_FORM_CODES.contains(systemFormCode)) {
            throw new GeneralException(FormErrorCode.FORM_NOT_FOUND);
        }
        return formRepository
                .findBySystemFormCodeAndStatusIn(systemFormCode, EVER_OPENED)
                .map(
                        form ->
                                PublicSystemFormMetaResponse.of(
                                        form, formReceiptPolicy.receiptStatusOf(form)))
                .orElseThrow(() -> new GeneralException(FormErrorCode.FORM_NOT_FOUND));
    }
}
