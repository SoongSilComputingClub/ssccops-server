package org.sscc.ssccopsserver.domain.form.service;

import java.util.List;

import org.sscc.ssccopsserver.domain.form.dto.PublicFormMetaResponse;
import org.sscc.ssccopsserver.domain.form.dto.PublicOpenFormResponse;

/*
 * 익명 폼 메타 조회 (ssccops#201). 공개 폼 링크를 메신저에 붙였을 때 크롤러가 카드를 만들
 * 수 있도록 제목·안내 문구만 내준다.
 *
 * PublicEventService의 짝이다 — 그쪽이 "PUBLISHED 밖의 행사는 존재하지 않는 것으로 답한다"를
 * 지키듯 여기는 **"접수를 연 적 없는 폼은 존재하지 않는 것으로 답한다"**를 지킨다.
 */
public interface PublicFormMetaService {

    /*
     * 접수를 연 적 있는(OPEN·CLOSED) 폼의 제목·안내 문구. DRAFT 폼과 없는 폼은 **둘 다**
     * 404 NOT_FOUND다 — 코드를 나누면 그 번호에 폼이 있다는 사실이 새어 나간다.
     */
    PublicFormMetaResponse getFormMeta(String formRef);

    /** 지금 접수 중인 공개 폼 — 마감이 가까운 것부터, 마감 없는 것은 뒤에 (ssccops#381) */
    List<PublicOpenFormResponse> getOpenForms();
}
