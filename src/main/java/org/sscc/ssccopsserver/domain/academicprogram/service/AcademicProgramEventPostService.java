package org.sscc.ssccopsserver.domain.academicprogram.service;

import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramEventPostResponse;
import org.sscc.ssccopsserver.domain.event.dto.EventImageUploadRequest;
import org.sscc.ssccopsserver.domain.event.dto.EventImageUploadResponse;
import org.sscc.ssccopsserver.domain.event.dto.EventIntroductionUpdateRequest;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/*
 * 스터디장이 자기 프로그램의 행사 게시물(소개 글)을 고치는 경로 (ssccops#618 · ADR-0069 참고).
 *
 * 세 동작 모두 **리더 본인만**이다(AcademicProgramOwnershipPolicy.isLeader — leadr_mbr_id 비교). 학술국장 ·
 * 국원도 403이다 — 그쪽은 관리자 페이지의 행사 편집(/v1/events · EVENT_MANAGE)을 쓴다.
 */
public interface AcademicProgramEventPostService {

    /** 조회. 잠겨 있어도 200이고 isEditable · lockReason만 바뀐다 */
    AcademicProgramEventPostResponse getEventPost(Long academicProgramId, MemberEntity requester);

    /** 제목 · 본문 · 대표 이미지 · 장소만 바꾼다. 수료 · 폐지 409 → 접수 종료 409 */
    AcademicProgramEventPostResponse updateEventPost(
            Long academicProgramId, EventIntroductionUpdateRequest request, MemberEntity actor);

    /** 본문 이미지 업로드 URL — 행사 이미지와 같은 키(events/{eventId}/…)와 같은 영구 주소다 */
    EventImageUploadResponse issueImageUploadUrl(
            Long academicProgramId, EventImageUploadRequest request, MemberEntity actor);
}
