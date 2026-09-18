package org.sscc.ssccopsserver.domain.file.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.file.code.FileTargetType;
import org.sscc.ssccopsserver.domain.file.entity.FileReferenceEntity;

public interface FileReferenceRepository extends JpaRepository<FileReferenceEntity, Long> {

    /*
     * 대상 하나에 붙은 참조 (#220). 지금 대상은 회차 하나뿐이고 회차당 1건이라 단건이다 —
     * 재업로드(UPSERT)가 갈아 끼울 대상을 찾는 자리이자, 회차 상세(#135)가 사진 유무를 싣는
     * 자리다.
     *
     * **대상당 여러 건이 정상인 대상이 생기면 여기에 목록 질의를 더한다.** 그때 이 단건 질의를
     * 그 대상에 쓰면 두 번째 행부터 조용히 사라지므로, 새 대상을 열 때 반드시 지나야 하는
     * 자리로 남겨 둔다.
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
