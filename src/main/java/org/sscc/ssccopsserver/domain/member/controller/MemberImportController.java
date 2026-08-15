package org.sscc.ssccopsserver.domain.member.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.dto.MemberImportPreviewResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberImportValidationResponse;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.service.MemberImportService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.authorization.RequireAuthority;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * CSV 회원 이관 사전 검증 API (#84 · 상위 ssccops#75).
 *
 * **클래스 레벨 @RequireAuthority(MEMBER_MANAGE)다.** 미리보기도 예외가 아니다 — 남의 명부 파일을
 * 올려 헤더와 앞 5행을 받아 보는 것 자체가 개인정보 열람이고, 두 엔드포인트는 같은 위저드의 앞뒤
 * 단계라 한쪽만 열어 둘 이유가 없다.
 *
 * 이관 실행은 여기 없다. 이 컨트롤러가 하는 일은 읽고 알려 주는 것뿐이며 mbr을 건드리지 않는다
 * (#85가 별도 엔드포인트로 맡는다).
 *
 * 요청은 multipart/form-data이고 mapping은 JSON **문자열** 파트다. 파일과 함께 와야 해서 본문
 * 전체를 JSON으로 둘 수 없고, 그래서 파싱은 서비스가 한다.
 */
@RestController
@RequestMapping("/v1/members/imports")
@RequireAuthority(AuthorityCode.MEMBER_MANAGE)
@RequiredArgsConstructor
public class MemberImportController {

    private final MemberImportService memberImportService;

    /*
     * 1단계 — 미리보기. mapping을 받지 않는다(추천 매핑을 **돌려주는** 자리다).
     *
     * 헤더 파싱을 웹에서 하지 않는 것이 이 엔드포인트의 존재 이유다. CSV 파서가 두 벌이 되면
     * 따옴표로 감싼 헤더("회장,프로젝트장")에서 해석이 갈려, 화면에서 매핑한 컬럼과 서버가 읽는
     * 컬럼이 어긋난다.
     */
    @Operation(
            summary = "CSV 이관 미리보기",
            description =
                    "CSV의 헤더 목록·추천 매핑·앞 5행을 돌려준다. recommendedMapping은 헤더 이름으로"
                            + " 짐작한 값이며 운영자가 화면에서 고쳐야 한다(짐작하지 못한 헤더는"
                            + " 빈 문자열). 파일은 CSV·최대 5MB·UTF-8(BOM 허용)이며 어기면 400"
                            + " INVALID_CSV_FILE, 데이터 행이 0건이면 400 EMPTY_CSV_FILE이다."
                            + " 아무것도 저장하지 않는다.")
    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<MemberImportPreviewResponse> preview(
            @RequestPart("file") MultipartFile file, @CurrentMember MemberEntity requester) {

        return ApiResponse.success(memberImportService.preview(file));
    }

    /*
     * 2단계 — 전량 검증. mapping을 @RequestPart가 아니라 @RequestParam으로 받는 것은 위저드가
     * FormData.append('mapping', json)로 보내는 값이 파트가 아니라 폼 필드이기 때문이다.
     */
    @Operation(
            summary = "CSV 이관 검증",
            description =
                    "매핑을 적용해 전량을 검증하고 fileToken·요약·행별 결과를 돌려준다. mapping은"
                            + " {\"이름\":\"mbrNm\"} 형식의 JSON 문자열이며 빈 값은 매핑하지 않음이다."
                            + " mbrNm·mbrGrdCd·mbrSttsCd가 매핑되지 않았거나 파일에 없는 헤더를"
                            + " 가리키면 400 CSV_MAPPING_INVALID다. rowNo는 원본 CSV의 줄 번호이며"
                            + " 헤더를 1행으로 센다. 연락처 누락은 오류가 아니라 warnings이고 그 행은"
                            + " okCount에 포함된다. **아무것도 저장하지 않는다** — 실제 등록은 별도"
                            + " 엔드포인트의 몫이다.")
    @PostMapping(value = "/validation", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<MemberImportValidationResponse> validate(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "mapping", required = false) String mapping,
            @CurrentMember MemberEntity requester) {

        return ApiResponse.success(memberImportService.validate(file, mapping));
    }
}
