package org.sscc.ssccopsserver.domain.academicprogram.service;

import java.util.List;

import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramMemberResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.RecruitmentSelectRequest;
import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;
import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseSummaryResponse;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/*
 * 팀원 명단과 모집 신청자·선발 (#138 · 학술관리_API설계.md §3.7).
 *
 * **새 테이블도 새 규칙도 만들지 않는다**(설계 결정 #3). 팀원은 event_ptcp이고 신청자는
 * form_rspns_hstry이며, 이 서비스가 하는 일은 "학술 활동 → 그 활동의 Event·Form"을 푸는 것과
 * 학술관리 쪽 인가·상태 전제를 거는 것뿐이다. 심사 규칙은 폼 도메인(#141)이, 명단 규칙은 행사
 * 도메인(ssccops#146)이 그대로 갖는다.
 *
 * 컨트롤러는 둘로 나뉘지만(팀원 조회는 인증만, 모집은 자격이 걸린다) 서비스는 나누지 않는다 —
 * 선발의 응답이 곧 갱신된 팀원 명단이라 두 쪽이 같은 행을 같은 모양으로 읽어야 하고, 나누면
 * 그 조립이 두 벌이 된다(FormResponseService가 응답자용·운영자용 컨트롤러를 함께 받치는 것과
 * 같은 판단).
 *
 * **인가 판정이 서비스에 있는 것은 신청자 조회 하나뿐이다.** 그쪽만 "스터디장 본인 또는
 * 학술국장"이라는 OR이고 @RequireAuthority로 표현되지 않아 AcademicProgramOwnershipPolicy를
 * 태운다. 선발은 ACADEMIC_PROGRAM_MANAGE 단일 권한이라 컨트롤러의 애노테이션이 이미 끊고,
 * 팀원 조회는 인증만 요구하므로 판정 자체가 없다.
 */
public interface AcademicProgramRecruitmentService {

    /*
     * 팀원 명단. 상태 필터를 생략하면 **취소(CANCELLED)를 포함한 전부**다 — 행사 참가자 명단과
     * 같은 규칙이며(D16 · 명단은 활동 이력으로 영구 보존한다), 취소자를 기본값에서 감추면
     * 지난 회차 출석부에는 있는데 명단에는 없는 사람이 생긴다.
     *
     * 모집 시작 여부를 보지 않는다 — 아직 아무도 뽑지 않은 활동의 빈 명단은 정상적인 답이고,
     * 이 조회는 모집이 아니라 활동 상세 화면의 일부다.
     */
    List<AcademicProgramMemberResponse> getMembers(
            Long academicProgramId, EventParticipantStatus participantStatus);

    /*
     * 신청자(= 연결된 모집 폼의 응답) 목록. 규칙을 복제하지 않고 FormResponseService.getResponses에
     * 위임한다 — 기본값(DRAFT 제외)·정렬·회원 조인이 폼 화면과 갈리면 같은 신청이 두 화면에서
     * 다른 순서·다른 범위로 보인다.
     *
     * 모집 전(APPROVED)이면 빈 목록이 아니라 409 RECRUITMENT_NOT_STARTED다.
     */
    List<FormResponseSummaryResponse> getApplications(
            Long academicProgramId, ResponseStatus responseStatus, MemberEntity requester);

    /*
     * 선발 확정 — 폼 응답 심사(ACCEPTED)와 event_ptcp 등록을 **한 트랜잭션**으로 묶는다.
     *
     * 나눌 수 없는 한 건인 것이 요점이다. 심사만 성공하면 "수락됐는데 팀원이 아닌" 신청자가
     * 남고, 등록만 성공하는 경우는 애초에 없다(등록은 ACCEPTED를 전제한다). 한 줄이라도
     * 실패하면 전부 되돌아가며, 그래서 이미 확정된 신청자를 다시 고르는 요청은 그 자리에서
     * 끊긴다(폼 응답이 이미 ACCEPTED라 종결이다).
     *
     * **정원은 검사하지 않는다**(설계 결정 #2 · wave2 D5와 같은 참고치 원칙). 초과해도 막지
     * 않고 서버 로그로만 남긴다 — 화면은 활동 상세의 cpctyMaxCnt와 이 응답의 확정 인원을
     * 비교해 경고한다.
     *
     * 응답은 갱신된 팀원 명단 전체다(고른 줄만이 아니다) — 선발 화면이 다음에 그리는 것이
     * 명단이고, 부분만 돌려주면 화면이 한 번 더 조회해야 한다.
     */
    List<AcademicProgramMemberResponse> selectMembers(
            Long academicProgramId, RecruitmentSelectRequest request, MemberEntity performer);
}
