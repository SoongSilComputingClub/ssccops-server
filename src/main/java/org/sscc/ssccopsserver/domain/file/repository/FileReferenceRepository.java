package org.sscc.ssccopsserver.domain.file.repository;

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
}
