package org.sscc.ssccopsserver.domain.academicprogram.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/*
 * 학술 활동 유형 등록·수정 공용 요청 (#130 · POST /v1/academic-program-types ·
 * PATCH /v1/academic-program-types/{typeCd}).
 *
 * typeCd는 PK이며 IDENTITY가 아니라 클라이언트가 지정한다(AuthorityCreateRequest.authrtCd와
 * 같은 이유) — 대문자·숫자·밑줄만 허용해 STUDY/PROJECT 시드와 같은 표기를 강제한다.
 *
 * 수정(PATCH)에서는 typeCd를 쓰지 않는다 — 경로의 값이 유일한 식별자이고, 본문의 typeCd는
 * 조용히 무시된다(FormEntity가 PUT 본문의 formSttsCd를 무시하는 것과 같은 태도). 코드를
 * 바꿔야 하면 새 유형을 만들고 이전 유형은 사용 여부를 끈다.
 *
 * 사용 여부는 여기 없다 — 목록의 토글이 /activation으로 따로 바꾸므로 폼 저장이 그 값을
 * 되돌리면 안 된다(SubWorkTypeSaveRequest와 같은 판단).
 */
public record AcademicProgramTypeSaveRequest(
        @NotBlank @Size(max = 30) @Pattern(regexp = "^[A-Z][A-Z0-9_]*$") String typeCd,
        @NotBlank @Size(max = 50) String typeNm,
        @NotNull Integer indctSeqno) {}
