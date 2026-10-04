package org.sscc.ssccopsserver.domain.file.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.file.code.FileTargetType;
import org.sscc.ssccopsserver.domain.file.entity.FileReferenceEntity;

public interface FileReferenceRepository extends JpaRepository<FileReferenceEntity, Long> {

    /*
     * 대상 하나에 붙은 참조 (#220). **대상당 1건인 대상 전용이다** — 회차 인증사진(SESSION)과
     * 규정 문서 원본(RAG_DOCUMENT). 재업로드(UPSERT)가 갈아 끼울 대상을 찾는 자리이자, 회차
     * 상세(#135)가 사진 유무를 싣는 자리다.
     *
     * **대상당 여러 건이 정상인 대상(CONTENT_POST·OPERATION)에는 아래 목록 질의를 쓴다.** 이 단건
     * 질의를 그 대상에 쓰면 두 번째 행부터 IncorrectResultSizeDataAccessException이다 — 대상의
     * 전부를 지우는 FileReferenceService.deleteByTarget이 그래서 목록 질의로 옮겨 갔다(#638).
     */
    Optional<FileReferenceEntity> findByTargetTypeAndTargetId(
            FileTargetType targetType, Long targetId);

    /*
     * 대상 하나에 붙은 참조 전부 (ssccops#381 · CONTENT_POST 갤러리). 위 주석이 예고한 «대상당
     * 여러 건이 정상인 대상»이 콘텐츠 포스트로 처음 생겼다. 순서는 id 오름차순 = 발급 순서다 —
     * file_rfrnc에 정렬 컬럼이 없고 갤러리 순서를 손으로 바꾸는 요구가 아직 없어 컬럼을 더하지
     * 않았다.
     */
    List<FileReferenceEntity> findAllByTargetTypeAndTargetIdOrderByIdAsc(
            FileTargetType targetType, Long targetId);

    /*
     * 대상 안의 한 건. 대상 조건을 함께 거는 것은 남의 포스트의 파일 id로 이 포스트의 갤러리를
     * 지우거나 표지로 삼는 경로를 만들지 않기 위해서다 — 없는 파일과 남의 파일이 같은 404다.
     */
    Optional<FileReferenceEntity> findByIdAndTargetTypeAndTargetId(
            Long id, FileTargetType targetType, Long targetId);
}
