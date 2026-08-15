package org.sscc.ssccopsserver.domain.member.service;

import org.springframework.web.multipart.MultipartFile;
import org.sscc.ssccopsserver.domain.member.dto.MemberImportPreviewResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberImportValidationResponse;

/*
 * CSV 회원 이관 **사전 검증** (#84 · 상위 ssccops#75).
 *
 * 이 서비스는 아무것도 쓰지 않는다. 실제 등록은 별도 이슈(#85)의 몫이며, 여기서 하는 일은
 * "넣기 전에 무엇이 들어가고 무엇이 걸리는지" 알려 주는 것뿐이다.
 */
public interface MemberImportService {

    /** 헤더 목록·추천 매핑·앞 5행. 파서를 서버 한 곳에 두기 위해 이 단계도 서버가 한다 */
    MemberImportPreviewResponse preview(MultipartFile file);

    /** 매핑을 적용한 전량 검증. mappingJson은 `{"이름":"mbrNm", ...}` 형식의 JSON 문자열이며 빈 값은 '매핑하지 않음'이다. */
    MemberImportValidationResponse validate(MultipartFile file, String mappingJson);
}
