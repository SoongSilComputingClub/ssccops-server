package org.sscc.ssccopsserver.domain.academicprogram.dto;

import java.time.LocalDate;
import java.util.List;

import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramTypeEntity;

/*
 * 기획안 폼 응답(rspns_cn)을 통째로 파싱한 결과 (#150). "승인 버튼을 누르면 무엇이 만들어지는가"
 * 한 벌이며, 이관(AcademicProgramMigrationService)과 검토 미리보기가 **같은 파서로 만든 같은
 * 값**을 본다.
 *
 * 엔티티가 아니라 record인 것은 파싱 결과가 저장 여부와 무관하게 존재해야 하기 때문이다 —
 * 미리보기는 아무것도 저장하지 않는다.
 *
 * type을 코드 문자열이 아니라 엔티티로 들고 있는 것은, 문자열 → 코드 매핑 실패가 이관 실패라는
 * 규칙 때문이다. 코드만 담으면 "매핑에 성공했지만 그 코드의 행이 없는" 상태를 표현할 수 있게
 * 되는데, 그런 상태는 존재해선 안 된다 — 기준정보에서 찾은 행이 있거나, 파싱이 실패하거나 둘뿐이다.
 *
 * 각 필드가 어느 문항에서 오는지는 ProposalResponseParser가 갖는다(ProposalFormSeed의 qitemId
 * 상수를 그대로 가리킨다).
 */
public record ProposalDraft(
        AcademicProgramTypeEntity type,
        String title,
        String goalContent,
        String prepContent,
        LocalDate periodBeginDate,
        LocalDate periodEndDate,
        String scheduleText,
        Integer capacityMinCount,
        Integer capacityMaxCount,
        String placeName,
        List<CurriculumItemDraft> curriculumItems) {}
