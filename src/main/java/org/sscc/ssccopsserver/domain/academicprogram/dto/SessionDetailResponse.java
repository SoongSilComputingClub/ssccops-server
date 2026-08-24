package org.sscc.ssccopsserver.domain.academicprogram.dto;

import java.time.LocalDate;
import java.util.List;

import org.sscc.ssccopsserver.domain.academicprogram.entity.AttendanceEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.CurriculumItemEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.FileReferenceEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.SessionEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/*
 * 회차 상세 응답(#135 · 학술관리_API설계.md §3.4). 제출(201)·재제출(200)·단건 조회(200)가 모두
 * 같은 모양이다 — 쓰기 직후 화면이 재조회 없이 같은 화면을 그릴 수 있어야 한다(회원가입 응답이
 * 세션 조회와 같은 DTO를 쓰는 것과 같은 판단).
 *
 * 계획 쪽 값(seqno·curriculumTtl·planDt)을 함께 싣는 것은 이 화면이 "계획 대비 실제"를 나란히
 * 보여주기 때문이다 — 클라이언트가 커리큘럼을 따로 한 번 더 부르지 않는다.
 *
 * presentCount·totalCount는 저장하지 않는 파생값이다. attendances를 세면 나오는 값을 굳이
 * 함께 내리는 것은 화면 상단의 "N/M 참석" 요약이 목록 응답에도 같은 이름으로 필요해서다
 * (SessionSummaryResponse) — 한쪽만 세면 두 화면의 숫자가 갈린다.
 */
public record SessionDetailResponse(
        Long sessionId,
        Long curriculumItemId,
        Integer seqno,
        String curriculumTtl,
        LocalDate planDt,
        LocalDate realDt,
        String cn,
        String noticeCn,
        String sttsCd,
        Long rgtrMbrId,
        String rgtrMbrNm,
        SessionFileReferenceResponse fileReference,
        List<SessionAttendanceResponse> attendances,
        int presentCount,
        int totalCount,
        String latestOpinion) {

    /*
     * fileReference는 출석 인증사진(#137)이 있으면 그 참조이고 없으면 null이다 — 사진을 아직
     * 올리지 않은 회차가 흔한 상태라 빈 껍데기를 만들어 내리지 않는다. latestOpinion은 회차
     * 승인·수정요청(#136)이 남긴 최신 academic_program_aprv(SESSION) 행의 사유이며, 아직
     * 검토되지 않은 회차는 null이다 — 둘 다 값을 만들어 내지 않고 조회 결과를 그대로 싣는다.
     */
    public static SessionDetailResponse of(
            SessionEntity session,
            List<AttendanceEntity> attendances,
            FileReferenceEntity fileReference,
            String latestOpinion) {
        CurriculumItemEntity curriculumItem = session.getCurriculumItem();
        MemberEntity registrant = session.getRegistrant();

        List<SessionAttendanceResponse> rows =
                attendances.stream().map(SessionAttendanceResponse::from).toList();
        int presentCount = (int) rows.stream().filter(SessionAttendanceResponse::presentYn).count();

        return new SessionDetailResponse(
                session.getId(),
                curriculumItem.getId(),
                curriculumItem.getSeqno(),
                curriculumItem.getTitle(),
                curriculumItem.getPlanDate(),
                session.getRealDate(),
                session.getContent(),
                session.getNoticeContent(),
                session.getStatus().name(),
                registrant.getId(),
                registrant.getName(),
                fileReferenceOf(fileReference),
                rows,
                presentCount,
                rows.size(),
                latestOpinion);
    }

    /*
     * 참조가 없으면 블록 자체를 내리지 않는다(null) — 화면은 이 값의 유무 하나로 "사진 있음/
     * 없음"을 가른다. 필드가 null인 껍데기를 내리면 그 판단이 fileUrl 검사로 한 겹 더 들어간다.
     */
    private static SessionFileReferenceResponse fileReferenceOf(FileReferenceEntity fileReference) {
        return fileReference == null
                ? null
                : new SessionFileReferenceResponse(
                        fileReference.getId(), fileReference.getFileUrl());
    }
}
