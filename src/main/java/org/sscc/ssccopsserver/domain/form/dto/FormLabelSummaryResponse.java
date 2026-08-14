package org.sscc.ssccopsserver.domain.form.dto;

import org.sscc.ssccopsserver.domain.form.entity.FormLabelEntity;

/*
 * 폼 응답에 실리는 라벨 요약. 목록 카드의 파란 알약과 상세의 라벨 줄이 같은 모양을 쓴다.
 *
 * 라벨 엔티티를 그대로 내보내지 않는 것은 use_yn·crt_dt가 폼 화면에 아무 의미가 없기 때문이다
 * (LY-03). 특히 use_yn은 "새로 달 수 있는가"라는 라벨 관리(#34)의 축이라, 이미 달린 라벨을
 * 보여주는 이 자리에 실으면 비활성 라벨을 숨겨야 하는지 화면이 헷갈리게 된다.
 *
 * 필드명은 데이터사전 표기(formLblId·lblNm) 그대로다 — 웹 타입(entities/form/model/types.ts)의
 * FormLbl과 키를 맞춰 두면 프론트가 매핑 계층 없이 그대로 쓴다.
 */
public record FormLabelSummaryResponse(Long formLblId, String lblNm) {

    public static FormLabelSummaryResponse from(FormLabelEntity label) {
        return new FormLabelSummaryResponse(label.getId(), label.getName());
    }
}
