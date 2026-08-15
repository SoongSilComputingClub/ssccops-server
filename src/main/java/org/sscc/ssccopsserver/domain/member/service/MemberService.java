package org.sscc.ssccopsserver.domain.member.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.sscc.ssccopsserver.domain.member.dto.AssignableMemberResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberDetailResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberGradeResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberProfileResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberRoleResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberSearchCondition;
import org.sscc.ssccopsserver.domain.member.dto.MemberSearchResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberSelfUpdateRequest;
import org.sscc.ssccopsserver.domain.member.dto.MemberSignupRequest;
import org.sscc.ssccopsserver.domain.member.dto.MemberStatusResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberUpdateRequest;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.security.AuthenticatedUser;

public interface MemberService {

    /*
     * 회원가입. 인증만 마친 사용자를 정식 회원으로 만든다 — mbr 행이 생기는 유일한 경로다.
     *
     * 인증 주체에서 오는 값(auth_user_id·이메일)과 요청 본문에서 오는 값(프로필)을 나눠 받는다.
     * 응답은 세션 조회의 member 블록과 같은 MemberProfileResponse다 — 가입 직후 프론트가
     * 세션을 다시 조회하지 않아도 되게 하려는 것이 이 API의 설계 의도이기 때문이다.
     */
    MemberProfileResponse signUp(AuthenticatedUser user, MemberSignupRequest request);

    /*
     * Supabase 인증 사용자 식별자로 연결된 회원을 조회한다. 아직 가입하지 않았으면 비어 있다.
     * 인증 경로에서 호출되므로 여기서 회원을 생성하지 않는다 — 생성은 회원가입 API의 책임이다.
     */
    Optional<MemberEntity> findByAuthUserId(UUID authUserId);

    /*
     * 본인 회원 정보. 등급·상태·현재 역할이 모두 지연 로딩이라 조회 트랜잭션 안에서 DTO로 굳혀
     * 돌려준다 — 인증 주체에 실린 MemberEntity는 준영속이라 그대로 꺼내 쓸 수 없다.
     */
    MemberProfileResponse getProfile(Long memberId);

    /*
     * 회원이 지금 맡고 있는 조직 역할 목록. 종료일이 지난 배정은 담지 않는다.
     *
     * 운영 도메인의 승인자·운영진 판정(#47)이 쓴다. 다른 도메인은 회원 Repository를 직접
     * 호출할 수 없으므로(AR-07·LY-10) 역할 조회의 진입점을 여기 하나로 둔다.
     * 대표 역할 여부(rprs_role_yn)는 화면 표시용이며 권한 판정에 쓰지 않는다.
     */
    List<MemberRoleResponse> findCurrentRoles(Long memberId);

    /*
     * 다른 도메인이 담당자·작성자 등으로 지정할 수 있는 회원인지 확인해 반환한다.
     * 탈퇴·제명 회원은 존재하더라도 비어 있는 값으로 돌아간다.
     * 지정 불가 사유를 어떤 오류로 볼지는 호출하는 도메인이 정하므로 예외 대신 Optional을 준다.
     */
    Optional<MemberEntity> findAssignableMember(Long memberId);

    /*
     * 회원 관리 목록 (GET /v1/members, #76). 검색·필터·정렬·커서 페이징을 한 번에 다룬다.
     *
     * 조건 해석(기준 코드 검증·커서 해독)은 요청 DTO가 이미 마친 상태로 들어온다 — 조회
     * 코드가 400을 던지지 않게 하기 위해서다 (LY-02).
     */
    MemberSearchResponse searchMembers(MemberSearchCondition condition);

    /*
     * 회원 단건 (GET /v1/members/{mbrId}, #76). 프로필에 현재 역할과 최근 변경 이력 3건을
     * 더해 돌려준다. 없는 회원은 404 MEMBER_NOT_FOUND다.
     *
     * 본인용 getProfile과 응답을 나눈 것은 담기는 것이 다르기 때문이다 (LY-03) —
     * capabilities는 본인 세션에서만 뜻이 있는 값이라 여기에는 없다.
     */
    MemberDetailResponse getMemberDetail(Long memberId);

    /*
     * 운영진의 회원 정보 수정 (PATCH /v1/members/{mbrId}, #77).
     *
     * 바꾸는 것은 요청 DTO에 있는 여섯 필드뿐이다 — 등급·상태는 이력을 함께 남겨야 해 전용
     * API가 따로 있고(#78), 학번은 updatable = false로 잠겨 있다. **막는 방법이 DTO에 필드를
     * 두지 않는 것**이라 이 메서드에는 무시하는 분기가 없다.
     *
     * 재학 회원의 학과·학년 필수는 가입·CSV 이관과 같은 규칙(AcademicProfilePolicy)을
     * 쓴다. 요청에는 상태가 없으므로 회원을 읽어 그 상태로 판정한다 — 없는 회원은 404,
     * 어긴 값은 400 VALIDATION_FAILED다.
     *
     * 응답이 조회(getMemberDetail)와 같은 MemberDetailResponse인 것은 수정 화면이 저장 직후
     * 상세를 다시 조회하지 않아도 되게 하기 위해서다.
     */
    MemberDetailResponse updateMember(Long memberId, MemberUpdateRequest request);

    /*
     * 본인의 회원 정보 수정 (PATCH /v1/members/me, #77).
     *
     * 대상은 인증 주체 본인이며 memberId는 컨트롤러가 @CurrentMember에서 꺼내 넘긴다 —
     * 경로에도 본문에도 대상을 넣을 자리가 없다는 것이 '남의 행을 고칠 수 없다'의 근거다.
     *
     * 운영진 경로와 달리 기수·이메일은 바꾸지 않는다(MemberSelfUpdateRequest 주석).
     * 응답은 세션 조회·가입과 같은 MemberProfileResponse다 — 저장 직후 웹이 세션을 다시
     * 조회하지 않아도 되게 하는 것이 그 설계의 의도이고, 프로필 수정도 같은 자리다.
     */
    MemberProfileResponse updateMyProfile(Long memberId, MemberSelfUpdateRequest request);

    /*
     * 담당자·책임자로 지정할 수 있는 회원 목록 (GET /v1/members/assignable, #76).
     *
     * 대상 판정은 단건판(findAssignableMember)과 **같은 규칙**을 쓴다. 호출하는 쪽에서
     * 탈퇴·제명 제외를 다시 구현하면 "목록에는 있는데 등록하면 거절되는 회원"이 생기므로
     * 목록 메서드를 여기 하나 더 두어 규칙을 한 곳에 남긴다.
     *
     * 응답에 연락처·이메일·학번이 없는 것은 이 목록이 MEMBER_MANAGE 없이 불리기 때문이다.
     */
    List<AssignableMemberResponse> findAssignableMembers();

    /*
     * 회원 등급 기준 코드 전체 (GET /v1/member-grades, #76). 표시 순번 오름차순이다.
     * 등급 필터·등급 변경 셀렉트가 이 목록으로 채워진다.
     */
    List<MemberGradeResponse> findAllGrades();

    /*
     * 회원 상태 기준 코드 전체 (GET /v1/member-statuses, #76). 가입 시 고를 수 없는 상태도
     * 포함한다 — 기준 코드 전체를 내리는 엔드포인트이기 때문이다.
     */
    List<MemberStatusResponse> findAllStatuses();
}
