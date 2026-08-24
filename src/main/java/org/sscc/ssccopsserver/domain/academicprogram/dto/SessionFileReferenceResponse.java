package org.sscc.ssccopsserver.domain.academicprogram.dto;

/*
 * 회차 상세(#135)의 출석 인증사진(회차당 1건, 학술관리_데이터모델.md §2 file_reference).
 *
 * 사진이 없는 회차는 이 블록 자체가 null이다 — 필드만 비운 껍데기를 내리면 화면이 "사진 있음"을
 * 판단하는 자리가 두 곳(블록 유무·fileUrl 유무)이 된다.
 *
 * **fileUrl이 가리키는 오브젝트가 실제로 있다는 보장은 없다.** 참조 행은 업로드 허가를 발급할
 * 때 태어나고 서버는 PUT을 관측하지 않는다(#137, FileReferenceEntity 주석) — 화면은 이미지가
 * 깨지면 다시 올린다(재업로드가 UPSERT다).
 */
public record SessionFileReferenceResponse(Long fileReferenceId, String fileUrl) {}
