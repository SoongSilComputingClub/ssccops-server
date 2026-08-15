package org.sscc.ssccopsserver.domain.member.service;

import org.springframework.web.multipart.MultipartFile;
import org.sscc.ssccopsserver.domain.member.dto.MemberImportExecutionResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberImportPreviewResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberImportValidationResponse;

/*
 * CSV 회원 이관 (#84 사전 검증 · #85 실행 · 상위 ssccops#75).
 *
 * 위저드의 세 단계가 그대로 세 메서드다: 미리보기 → 검증 → 실행. **앞의 둘은 아무것도 쓰지
 * 않고 마지막 하나만 쓴다** — mbr에 행을 넣는 경로는 회원가입과 여기 둘뿐이다.
 *
 * 세 단계가 한 인터페이스에 있는 것은 파서·매핑·검증기를 공유하기 때문이다. 실행이 자기 규칙을
 * 따로 가지면 검증에서 통과한 행이 실행에서 막힌다.
 */
public interface MemberImportService {

    /** 헤더 목록·추천 매핑·앞 5행. 파서를 서버 한 곳에 두기 위해 이 단계도 서버가 한다 */
    MemberImportPreviewResponse preview(MultipartFile file);

    /** 매핑을 적용한 전량 검증. mappingJson은 `{"이름":"mbrNm", ...}` 형식의 JSON 문자열이며 빈 값은 '매핑하지 않음'이다. */
    MemberImportValidationResponse validate(MultipartFile file, String mappingJson);

    /**
     * 검증을 다시 돌린 뒤 통과한 행을 mbr에 넣는다 (#85). **행 단위**라 한 행의 실패가 다른 행을 되돌리지 않는다.
     *
     * @param fileToken 검증 응답이 준 값. 지금 올라온 파일의 해시와 다르면 409 IMPORT_FILE_MISMATCH로 거절한다 — 확인한 파일과 넣는
     *     파일이 달라지면 사전 검증이 의미를 잃는다
     * @param operatorId 요청한 운영자의 mbr_id. 등급·상태 최초 이력의 chnrg_mbr_id가 된다
     */
    MemberImportExecutionResponse importMembers(
            MultipartFile file, String mappingJson, String fileToken, Long operatorId);
}
