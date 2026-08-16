package org.sscc.ssccopsserver.domain.member.service;

import org.sscc.ssccopsserver.domain.member.dto.MemberGradeChangeRequest;
import org.sscc.ssccopsserver.domain.member.dto.MemberGradeChangeResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberStatusChangeRequest;
import org.sscc.ssccopsserver.domain.member.dto.MemberStatusChangeResponse;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/*
 * 회원 등급·상태를 바꾸고 그 이력을 남기는 서비스 (#78).
 *
 * ── 왜 MemberService가 아니라 별도 인터페이스인가 ────────────────
 * 탈퇴·제명 전이의 경고에 '담당 중인 하위 업무' 건수가 실리므로 운영 도메인의 SubWorkService가
 * 필요한데, SubWorkServiceImpl은 담당자 실재 확인을 위해 이미 MemberService를 주입받는다
 * (AR-07·LY-10). 이 두 메서드를 MemberServiceImpl에 넣으면
 * MemberServiceImpl → SubWorkServiceImpl → MemberServiceImpl 고리가 되어 생성자 주입이
 * 순환하고 애플리케이션이 아예 뜨지 않는다. 빈을 나누면 고리가 끊긴다 —
 * MemberChangeServiceImpl은 아무도 주입받지 않기 때문이다.
 *
 * 나누고 나서 보면 경계도 맞는다. MemberService는 '회원을 만들고 읽는' 일이고 이쪽은
 * '회원의 자격을 바꾸고 그 사실을 이력에 남기는' 일이다.
 *
 * ── 왜 회원 정보 수정(PATCH)에 등급·상태 필드를 두지 않는가 ────────
 * "등급을 바꾼다"와 "이력을 남긴다"는 나눌 수 없는 한 건이다. 같은 API에 섞으면 이력 없이
 * 등급이 바뀌는 경로가 반드시 생긴다 — 그래서 전용 엔드포인트를 판다.
 */
public interface MemberChangeService {

    /*
     * 회원 등급을 바꾸고 mbr_grd_hstry에 한 줄 남긴다 (POST /v1/members/{memberId}/grade-changes).
     *
     * mbr.mbr_grd_cd 갱신과 이력 INSERT가 **한 트랜잭션**이다. 이력 저장이 실패하면 등급 갱신도
     * 되돌아간다 — 등급만 바뀌고 이력이 없으면 그 승급은 근거를 잃고, 이력 행은
     * updatable = false로 잠겨 있어 나중에 채워 넣을 경로도 없다.
     *
     * changer는 인증 주체이며 요청 본문이 아니라 토큰에서 온다 (LY-05). 없는 회원은 404,
     * 기준 코드 밖의 등급은 400 INVALID_CODE_VALUE, 지금과 같은 등급은 400 NO_CHANGE다.
     */
    MemberGradeChangeResponse changeGrade(
            Long memberId, MemberGradeChangeRequest request, MemberEntity changer);

    /*
     * 회원 상태를 바꾸고 mbr_stts_hstry에 한 줄 남긴다
     * (POST /v1/members/{memberId}/status-changes). 트랜잭션 경계와 오류는 changeGrade와 같다.
     *
     * 탈퇴·제명으로 전이해도 역할을 끝내거나 담당 업무를 정리하지 않는다 — 대신 남아 있는
     * 것들을 경고로 실어 화면이 사람에게 알린다 (MemberChangeWarningResponse 주석).
     */
    MemberStatusChangeResponse changeStatus(
            Long memberId, MemberStatusChangeRequest request, MemberEntity changer);
}
