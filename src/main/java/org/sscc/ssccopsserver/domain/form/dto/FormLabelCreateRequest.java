package org.sscc.ssccopsserver.domain.form.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/*
 * 라벨 생성 요청 (POST /v1/form-labels).
 *
 * 이름 하나만 받는다. use_yn을 받지 않는 것은 만들자마자 비활성인 라벨이 쓸모가 없고,
 * 그 상태는 토글 엔드포인트가 만들면 되기 때문이다 (FormLabelEntity.create 참고).
 *
 * 50자 상한은 lbl_nm 컬럼 길이(V50) 그대로다 — DB가 자르거나 거절하기 전에 400으로 알려야
 * 어느 값이 문제인지 화면이 안내할 수 있다.
 */
public record FormLabelCreateRequest(@NotBlank @Size(max = 50) String lblNm) {}
