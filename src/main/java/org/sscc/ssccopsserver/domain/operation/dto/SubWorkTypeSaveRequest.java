package org.sscc.ssccopsserver.domain.operation.dto;

import java.util.List;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.sscc.ssccopsserver.domain.operation.entity.AuthorizerRole;

/*
 * 하위 업무 유형 등록·수정 공용 요청 (OPS-019 · POST /v1/sub-work-types ·
 * PATCH /v1/sub-work-types/{subWorkTypeId}).
 *
 * 두 요청이 같은 DTO를 쓰는 것은 관리 화면의 '새 하위 업무 유형'과 '하위 업무 유형 수정'이
 * 같은 폼이기 때문이다. 수정은 부분 수정이 아니라 폼 전체 저장이라 생략한 값은 지워진다.
 *
 * 기준 금액·지출 여부는 받지 않는다 — 화면에서 입력란이 빠졌고, 두 컬럼은 이 API의 범위 밖이다.
 * 사용 여부도 여기 없다. 목록의 토글이 /activation으로 따로 바꾸므로 폼 저장이 그 값을
 * 되돌리면 안 된다.
 *
 * 승인 정책 조합(승인 필요인데 승인자 없음, 정족수인데 인원 없음)은 여기서 @AssertTrue로
 * 잡지 않고 엔티티가 판단한다. 등록·수정 두 경로가 같은 불변식을 지켜야 하고, 승인이 필요
 * 없을 때 남은 값을 '거절'이 아니라 '정리'하는 규칙도 엔티티에 함께 있어야 하기 때문이다.
 */
public record SubWorkTypeSaveRequest(
        @NotBlank @Size(max = 100) String typeName,
        @NotNull Boolean approvalNeeded,
        AuthorizerRole authorizerRoleCode,
        @NotNull Boolean minAgreeCountNeeded,
        /*
         * 승인자가 최종 승인하기 전에 모여야 하는 찬성 수. 1도 유효하다 — 단독(투표 없이
         * 승인자 단독 결재)과 다른 설정이다 (POL-007 O-03 확정).
         */
        @Min(1) Integer minAgreeCount,
        List<String> completionCheckArticles) {

    /** 승인자 역할은 화면의 '-' 칩이 곧 미지정이라 null이 정상값이다 */
    public String authorizerRoleCodeName() {
        return authorizerRoleCode == null ? null : authorizerRoleCode.name();
    }
}
