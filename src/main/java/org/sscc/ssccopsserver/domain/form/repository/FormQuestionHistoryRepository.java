package org.sscc.ssccopsserver.domain.form.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormQuestionHistoryEntity;

/*
 * 문항 구성 이력 (#140).
 *
 * 조회 API는 아직 없다 — 이번 이슈는 "무엇이 언제 바뀌었는가"를 남기는 데까지이고, 옛 버전
 * 구성으로 응답을 다시 렌더하는 것은 범위 밖이다(응답 표시가 qitemId 기준이라 대체로 동작하고,
 * 이력이 남아 있으므로 필요해지면 그때 연다). 정렬을 버전 오름차순으로 고정해 두는 것은
 * 화면이 붙을 때 목록 순서가 화면마다 달라지지 않게 하기 위해서다.
 */
public interface FormQuestionHistoryRepository
        extends JpaRepository<FormQuestionHistoryEntity, Long> {

    List<FormQuestionHistoryEntity> findAllByFormOrderByQuestionVersionAsc(FormEntity form);
}
