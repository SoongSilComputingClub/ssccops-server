package org.sscc.ssccopsserver.domain.operation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/*
 * 완료 체크리스트 항목 추가·문구 수정 공용 요청 (#307).
 *
 * 두 요청이 같은 DTO를 쓰는 것은 보내는 값이 문구 하나로 같기 때문이다. 추가는 그 문구로
 * 새 항목을 만들고 수정은 기존 항목의 문구를 덮는다 — 나눠 두면 한쪽에만 길이 제한이 붙는
 * 날이 온다.
 *
 * 체크 여부(isCompleted)는 받지 않는다. 새 항목은 언제나 미완료로 시작하고(등록 시 유형에서
 * 복사되는 항목과 같다), 문구 수정은 체크 상태를 건드리지 않는다 — 체크·해제는 OPS-013의
 * 자기 엔드포인트가 맡는다.
 *
 * 순서(sortOrder)도 받지 않는다. 추가는 항상 끝에 붙으며, 순서 변경 API를 두지 않은 이유는
 * SubWorkController의 체크리스트 절 주석에 있다.
 *
 * 길이 상한 200은 유형의 완료 점검 항목(sub_work_type.cmptn_chck_artcl_cn)에 적히는 한 줄과
 * 같은 쓰임이라 그 화면의 한 줄 입력에 맞춘다. 컬럼은 TEXT라 DB가 막아 주지 않으므로 여기서
 * 막는다 — 체크리스트 한 줄이 화면을 넘기면 목록 자체를 읽을 수 없게 된다.
 */
public record SubWorkChecklistItemSaveRequest(@NotBlank @Size(max = 200) String article) {}
