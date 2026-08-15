package org.sscc.ssccopsserver.domain.operation.dto;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.hibernate.validator.constraints.URL;
import org.sscc.ssccopsserver.domain.operation.entity.OperationPriority;

/*
 * 하위 업무 수정 요청 (OPS-030 · PATCH /v1/sub-works/{subWorkId}). 등록(OPS-007)과 같은
 * 확장 속성 필드를 받되 두 가지를 뺀다:
 *
 * - **workId** — 다른 상위 업무로 옮기는 기능이 아니다. 진행률 집계(work_prgrs_rt)가
 *   상위 업무별로 계산되는데, 옮기면 두 상위 업무의 진행률이 함께 재계산돼야 하고
 *   이력(sub_work_stts_hstry)이 어느 상위 업무 소속이었는지도 갈린다 — 등록 하나로 끝나던
 *   경계를 이 API 하나가 다시 흔든다.
 * - **subWorkTypeId** — 유형이 바뀌면 승인 필요 여부·승인자·정족수·완료 점검 항목이 통째로
 *   달라지는데, 그 값들은 등록 시점에 이미 sub_work·sub_work_chck_list에 복사돼 있다
 *   (#43 소급 금지). 유형 재지정을 허용하려면 체크리스트를 다시 만들고 진행 중인 승인
 *   절차를 어떻게 할지부터 정해야 하는, 이 API의 범위를 넘는 결정이다.
 *
 * workStatus·approvalStatus도 없다(POL-003과 같은 판단) — 상태는 전이 액션 엔드포인트
 * (OPS-010)로만 바뀐다. completionCriteria(완료 기준 내용)는 등록 화면에 입력란이 없어
 * 여태 서버가 항상 NULL로 채웠는데(#70), 처음으로 값을 넣을 수 있는 경로가 이 API다.
 *
 * 등록과 마찬가지로 PATCH이지만 **전체 교체**다 — content·completionCriteria·externalLink처럼
 * 선택 입력인 필드도 생략하면 지우는 것으로 본다.
 */
public record SubWorkUpdateRequest(
        @NotBlank @Size(max = 256) String title,
        @NotNull @Positive Long ownerId,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        OffsetDateTime dueAt,
        OperationPriority priority,
        String content,
        String completionCriteria,
        @Size(max = 200) @URL String externalLink) {

    // 기간 역전 차단. 한쪽만 주어진 경우는 검사 대상이 아니다 (둘 다 선택 입력)
    @AssertTrue(message = "종료 일시는 시작 일시보다 빠를 수 없습니다.")
    public boolean isPeriodValid() {
        return startAt == null || endAt == null || !endAt.isBefore(startAt);
    }
}
