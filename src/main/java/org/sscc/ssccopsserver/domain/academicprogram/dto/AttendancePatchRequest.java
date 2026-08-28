package org.sscc.ssccopsserver.domain.academicprogram.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

/*
 * 출석 정정 요청 (#137 · PATCH .../attendances).
 *
 * **줄 하나의 모양은 회차 기록 제출(#135)과 같으므로 SessionAttendanceSubmitRequest를 그대로
 * 쓴다.** 이름만 다른 record를 하나 더 두면 "출석 한 줄"의 어휘가 두 벌이 되고, atndYn을
 * Boolean으로 둔 이유(빠뜨린 것과 결석을 구별한다) 같은 판단이 한쪽에만 남는다.
 *
 * 비어 있는 목록은 거절한다. 아무것도 바꾸지 않는 정정은 요청이 잘못 조립됐다는 뜻이고,
 * 200과 함께 "0건 갱신"을 돌려주면 화면은 성공했다고 읽는다.
 */
public record AttendancePatchRequest(
        @NotEmpty(message = "attendances는 한 건 이상이어야 합니다.") @Valid
                List<SessionAttendanceSubmitRequest> attendances) {}
