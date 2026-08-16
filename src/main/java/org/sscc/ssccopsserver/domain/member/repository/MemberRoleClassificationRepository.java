package org.sscc.ssccopsserver.domain.member.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleClassificationEntity;

public interface MemberRoleClassificationRepository
        extends JpaRepository<MemberRoleClassificationEntity, String> {

    /*
     * 분류 목록(#80). 정렬은 indct_seqno이며 그 값이 같으면 코드로 끊는다 — 순번은 UNIQUE가
     * 아니라서(운영진이 같은 값을 두 분류에 넣을 수 있다) 동률을 끊지 않으면 목록의 순서가
     * 요청마다 달라지고 화면의 드래그 정렬이 되돌아간 것처럼 보인다.
     *
     * use_yn이 없으므로(데이터사전) 필터 인자도 없다. 분류는 전부 내려간다.
     */
    List<MemberRoleClassificationEntity> findAllByOrderByDisplayOrderAscCodeAsc();
}
