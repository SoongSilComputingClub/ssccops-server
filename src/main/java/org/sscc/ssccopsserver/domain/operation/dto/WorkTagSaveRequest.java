package org.sscc.ssccopsserver.domain.operation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/*
 * 업무 태그 생성·이름 변경 요청 (POST /v1/work-tags · PATCH /v1/work-tags/{workTagId}).
 *
 * 두 요청이 이름 하나로 같아 한 record를 쓴다(SubWorkTypeSaveRequest와 같은 판단). 필드명 tagNm은
 * 컬럼명 그대로다 — 폼 라벨(lblNm)과 키 모양을 맞춰 두면 웹이 칩 선택기를 한 벌로 쓴다.
 *
 * 50자 상한은 tag_nm 컬럼 길이(V50) 그대로다 — DB가 거절하기 전에 400으로 알려야 화면이 어느 값이
 * 문제인지 안내할 수 있다. 앞뒤 공백은 서비스가 걷는다.
 */
public record WorkTagSaveRequest(@NotBlank @Size(max = 50) String tagNm) {}
