package org.sscc.ssccopsserver.domain.member.dto;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.sscc.ssccopsserver.domain.member.code.MemberHistorySource;
import org.sscc.ssccopsserver.global.apipayload.code.error.CommonErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/*
 * 회원 변경 이력 조회(GET /v1/members/{memberId}/histories)의 쿼리 파라미터 (#82).
 *
 * 파라미터는 type 하나뿐이다. 커서·size가 없는 것은 **이 목록에 페이징을 두지 않기로**
 * 했기 때문이며 그 근거는 MemberHistoryServiceImpl 주석에 적어 두었다.
 *
 * type을 enum이 아니라 문자열 목록으로 받는 이유는 MemberSearchCondition과 같다 — 바인딩
 * 단계에서 enum 변환이 실패하면 스프링의 TypeMismatch 처리로 넘어가 ApiResponse 봉투가 아닌
 * 응답이 나가고 오류 코드도 붙지 않는다. 이슈가 정한 400 VALIDATION_FAILED로 끊으려면
 * 문자열로 받아 여기서 해석해야 한다.
 *
 * 여기서 VALIDATION_FAILED를 쓰고 회원 목록의 등급·상태 필터가 INVALID_CODE_VALUE를 쓰는
 * 것은 다루는 값이 다르기 때문이다. 저쪽은 기준 코드 테이블(mbr_grd·mbr_stts)의 값이라
 * "기준 코드에 없는 값"이 정확한 안내지만, type은 이 API가 정의한 세 글자짜리 어휘라
 * 기준정보와 무관한 형식 오류다.
 */
public record MemberHistorySearchCondition(List<String> type) {

    /*
     * 조회할 출처. **생략하면 전부**다 — 이력 화면은 기본이 통합 타임라인이고, 아무것도
     * 고르지 않은 상태를 '아무것도 보지 않음'으로 읽으면 첫 진입이 늘 빈 목록이 된다.
     *
     * 빈 문자열(화면의 '전체' 선택이 그렇게 온다)은 필터를 걸지 않겠다는 뜻으로 보고 건너뛴다.
     * 중복은 EnumSet이 걷어낸다.
     */
    public Set<MemberHistorySource> sources() {
        if (type == null || type.isEmpty()) {
            return EnumSet.allOf(MemberHistorySource.class);
        }

        Set<MemberHistorySource> sources = EnumSet.noneOf(MemberHistorySource.class);
        for (String value : type) {
            String code = value == null ? null : value.strip();
            if (code == null || code.isEmpty()) {
                continue;
            }
            try {
                sources.add(MemberHistorySource.valueOf(code));
            } catch (IllegalArgumentException ex) {
                throw new GeneralException(CommonErrorCode.VALIDATION_FAILED);
            }
        }
        return sources.isEmpty() ? EnumSet.allOf(MemberHistorySource.class) : sources;
    }
}
