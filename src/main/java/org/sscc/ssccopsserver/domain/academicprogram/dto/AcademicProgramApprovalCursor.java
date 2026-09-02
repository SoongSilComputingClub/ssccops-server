package org.sscc.ssccopsserver.domain.academicprogram.dto;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.sscc.ssccopsserver.domain.academicprogram.code.error.AcademicProgramErrorCode;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramApprovalEntity;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/*
 * 승인 이력 목록(#139)의 커서. 형식은 AcademicProgramCursor·SessionCursor와 같은 Base64이지만
 * 싣는 것이 식별자 하나뿐이다.
 *
 * 정렬 표기를 함께 담지 않는 것은 이 목록의 순서가 하나뿐이기 때문이다 — 정렬 키가 곧 식별자
 * (aprv_id 내림차순)라 대조할 표기가 없고, 상수와 대조하는 자리를 만들면 규칙이 아니라 형식만
 * 늘어난다. 정렬을 고를 수 있게 되면 그때 다른 두 커서처럼 표기를 싣는다.
 *
 * 정렬 키를 처리 일시(aprv_dt)가 아니라 식별자로 잡은 이유는 AcademicProgramApprovalRepository
 * .findFirstBySessionIdAndPointOrderByIdDesc의 주석과 같다 — 그 컬럼은 PENDING이면 NULL이라
 * 아직 처리되지 않은 최신 행이 정렬에서 뒤로 밀린다.
 */
public record AcademicProgramApprovalCursor(Long approvalId) {

    public static AcademicProgramApprovalCursor of(AcademicProgramApprovalEntity lastRow) {
        return new AcademicProgramApprovalCursor(lastRow.getId());
    }

    public String encode() {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(String.valueOf(approvalId).getBytes(StandardCharsets.UTF_8));
    }

    public static AcademicProgramApprovalCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            String plain =
                    new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            return new AcademicProgramApprovalCursor(Long.parseLong(plain));
        } catch (RuntimeException ex) {
            throw new GeneralException(AcademicProgramErrorCode.INVALID_CURSOR);
        }
    }
}
