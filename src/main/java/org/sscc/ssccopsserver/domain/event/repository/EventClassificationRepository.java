package org.sscc.ssccopsserver.domain.event.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.event.entity.EventClassificationEntity;

public interface EventClassificationRepository
        extends JpaRepository<EventClassificationEntity, String> {

    List<EventClassificationEntity> findAllByOrderByDisplayOrderAsc();

    /*
     * 분류 관리 목록(ssccops#140). 표시 순번 동률을 코드로 끊는다 — 정렬이 안정되지 않으면
     * 관리 화면의 행 순서가 요청마다 달라진다 (MemberRoleClassificationRepository 선례).
     */
    List<EventClassificationEntity> findAllByOrderByDisplayOrderAscCodeAsc();
}
