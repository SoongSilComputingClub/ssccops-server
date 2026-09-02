package org.sscc.ssccopsserver.domain.academicprogram.dto;

import java.time.LocalDate;

/*
 * 기획안의 커리큘럼 한 줄을 파싱한 결과 (#150). 아직 엔티티가 아니다 —
 * CurriculumItemEntity로 굳으려면 AcademicProgram이 먼저 있어야 하는데, 파싱은 그보다 먼저
 * (검토 화면의 미리보기 시점에도) 일어난다.
 *
 * 필드 이름을 컬럼(seqno · ttl · plan_ymd)과 같게 두는 것은 이 값이 그 컬럼으로 그대로 들어가기
 * 때문이고, 미리보기 응답도 같은 이름을 쓴다 — 검토 화면이 보는 이름과 저장되는 이름이 갈리면
 * "미리보기와 실제 이관이 같다"는 이 이슈의 전제를 눈으로 확인할 수 없다.
 *
 * planDate가 null일 수 있는 것은 커리큘럼 줄 포맷이 날짜를 생략할 수 있게 두었기 때문이다
 * (ProposalFormSeed.CURRICULUM_LINE_FORMAT) — crclm_artcl.plan_ymd도 NULL 허용이다.
 */
public record CurriculumItemDraft(int seqno, String ttl, LocalDate planYmd) {}
