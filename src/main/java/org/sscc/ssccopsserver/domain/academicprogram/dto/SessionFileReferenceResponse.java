package org.sscc.ssccopsserver.domain.academicprogram.dto;

/*
 * 회차 상세(#135)의 출석 인증사진(회차당 1건, 학술관리_데이터모델.md §2 file_reference).
 *
 * file_reference 테이블·엔티티는 아직 없다 — 업로드 경로(presigned URL 발급)와 함께 #137이
 * 만든다. 그런데도 계약에 자리를 비워 두는 것은 화면이 사진 유무를 상세 응답 하나로 판단하기
 * 때문이다. 지금은 이 값이 언제나 null이며, #137이 조회를 채운다.
 */
public record SessionFileReferenceResponse(Long fileReferenceId, String fileUrl) {}
