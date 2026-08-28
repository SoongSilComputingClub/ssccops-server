package org.sscc.ssccopsserver.domain.academicprogram.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.academicprogram.entity.FileReferenceEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.SessionEntity;

public interface FileReferenceRepository extends JpaRepository<FileReferenceEntity, Long> {

    /*
     * 회차당 1장이므로 조회도 단건이다(uk_file_rfrnc_sesn). 재업로드(UPSERT)가 갈아 끼울
     * 대상을 찾는 자리이자, 회차 상세(#135)가 사진 유무를 싣는 자리다.
     *
     * 엔티티가 아니라 식별자로 찾는 오버로드를 함께 두지 않는다 — 두 경로 모두 이미 회차를
     * 활동 범위로 좁혀 읽은 뒤에 부르므로 SessionEntity를 들고 있다.
     */
    Optional<FileReferenceEntity> findBySession(SessionEntity session);
}
