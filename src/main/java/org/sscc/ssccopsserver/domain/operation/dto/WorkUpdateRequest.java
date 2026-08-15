package org.sscc.ssccopsserver.domain.operation.dto;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.sscc.ssccopsserver.domain.operation.entity.OperationPriority;
import org.sscc.ssccopsserver.domain.operation.entity.WorkType;

/*
 * 업무 수정 요청 (OPS-004 · PATCH /v1/works/{workId}). 등록(OPS-002)과 같은 필드
 * 구성이다 — 화면의 수정 폼이 등록 폼과 같은 입력란을 그대로 재사용한다.
 *
 * workStatus는 이 요청에 없다(POL-003) — PATCH로 상태를 바꾸는 경로를 열면 전이 액션
 * 엔드포인트(OPS-005)와 상태를 바꾸는 길이 둘이 되어 전이표 검사를 우회할 수 있다.
 * 진행률(work_prgrs_rt)도 하위 업무에서 파생하는 값이라 여기서 받지 않는다.
 *
 * 등록과 달리 본문 없음("필드 생략")과 "비운다"를 구별할 필요가 없는 필드들이라 PATCH이지만
 * **전체 교체**다(AuthorityUpdateRequest와 같은 판단) — review처럼 선택 입력인 필드도 생략하면
 * 지우는 것으로 본다. 부분 수정으로 두면 "review를 안 보냈다"와 "review를 빈 문자열로
 * 지웠다"를 record가 구별할 수 없어 화면이 총평을 지울 방법이 없어진다.
 */
public record WorkUpdateRequest(
        @NotBlank @Size(max = 256) String title,
        @NotNull WorkType itemType,
        @NotNull @Positive Long ownerId,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        OperationPriority priority,
        String review) {

    // 기간 역전 차단. 한쪽만 주어진 경우는 검사 대상이 아니다 (둘 다 선택 입력)
    @AssertTrue(message = "종료 일시는 시작 일시보다 빠를 수 없습니다.")
    public boolean isPeriodValid() {
        return startAt == null || endAt == null || !endAt.isBefore(startAt);
    }
}
