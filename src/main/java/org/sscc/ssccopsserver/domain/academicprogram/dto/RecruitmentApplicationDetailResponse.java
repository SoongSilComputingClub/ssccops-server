package org.sscc.ssccopsserver.domain.academicprogram.dto;

import java.time.OffsetDateTime;

import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;
import org.sscc.ssccopsserver.domain.event.entity.EventParticipantEntity;
import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseDetailResponse;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
import org.sscc.ssccopsserver.domain.form.entity.ResponseContent;

/*
 * 리더가 읽는 지원서 한 건 (ssccops#625 · GET .../recruitment/applications/{formRspnsId}).
 *
 * 스터디장·프로젝트장·트랙장이 자기 프로그램에 들어온 지원서의 **답**을 LMS에서 읽기 전용으로 본다.
 * 신청자 목록(RecruitmentApplicationResponse)은 #138부터 리더에게 열려 있었지만 요약뿐이었고, 답은
 * 운영자용 응답 상세(GET /v1/forms/{formId}/responses/{id})에만 있어 RESPONSE_REVIEW 없는 리더가 닿지
 * 못했다(리더 역할에는 권한을 붙일 수 없다 · ADR-0067).
 *
 * ── 운영자용 상세(FormResponseDetailResponse)를 그대로 내리지 않는다 ──
 * 필드를 **고른다**. 빠진 것과 그 이유:
 *
 *   telno(연락처)         → 지원자 record에 자리 자체가 없다(RecruitmentApplicantResponse 주석 · 사용자 결정)
 *   reviewHistories      → 학술국장의 처리 이력·검토 의견은 싣지 않는다. 검토 의견은 학술국장이 지원자에게
 *                          쓴 말이고 리더는 제3자다(사용자 결정 2026-11-09 — «상태만 보인다»). 상태는
 *                          rspnsSttsCd(응답)와 ptcpSttsCd(선발)로 충분하다
 *   sbmsnSeq(제출 회차)   → 이력의 각 줄이 몇 회차에 대한 것이었는지 읽는 기준점이다(그 DTO 주석). 이력이
 *                          없으니 기준점도 쓸 데가 없다
 *   academicProgramPreview → 기획안 승인 미리보기라 모집 폼에서는 언제나 null이다
 *
 * 한 record를 공유하면 운영자용에 필드가 하나 늘 때마다 리더 경로로 함께 새어 나간다 — 응답자용
 * MyFormResponseDetailResponse(#177)를 따로 둔 것과 같은 판단이다. 그래서 RecruitmentApplicationResponse와
 * 달리 «폼 쪽이 늘면 여기도 는다»를 시험하지 않고, 거꾸로 **이 목록이 고정돼 있음**을 시험한다
 * (RecruitmentApplicationDetailResponseTest — 웹 ssccops-web#826이 이 이름 그대로 쓴다).
 *
 * ── 더한 것 ──
 * qitemCpstCn(문항 구성): 답만으로는 화면이 그 답을 어느 문항에 붙일지 모른다. 모집 폼 조회
 * (GET .../recruitment/form)를 따로 부르게 하면 두 응답 사이에 시점이 갈릴 수 있다 — 응답자용 상세가 같은
 * 이유로 문항 구성을 함께 싣는다(ssccops#221). 접수가 시작되면 리더 경로로는 문항을 고칠 수 없지만(#483)
 * 학술국장의 폼 편집은 열려 있어 «답한 시점의 구성»과 다를 수 있다 — 그래도 응답이 쓰는 qitemId를 지우거나
 * 바꾸는 저장은 409 QUESTION_ITEM_IN_USE라(폼 도메인) 답이 붙을 문항은 남는다.
 *
 * eventPtcpId·ptcpSttsCd(참가 상태): 신청자 목록과 같은 «회원 기준» 값이다(RecruitmentApplicationResponse
 * 주석) — 목록에서 «확정»으로 본 사람을 열었는데 상세가 «미선발»이면 안 된다. 미선발이면 둘 다 null이고
 * 서버가 대체값을 만들지 않는다.
 *
 * prevFormRspnsId·nextFormRspnsId: 목록 기본 조회(작성 중 제외 · 제출 일시 내림차순)와 같은 순서·범위의
 * 이웃이다. 계산은 폼 도메인의 상세 조회가 한 그대로 옮긴다 — 순서 규칙을 이쪽에 한 벌 더 두지 않는다.
 */
public record RecruitmentApplicationDetailResponse(
        Long formRspnsId,
        int rspnsSeq,
        ResponseStatus rspnsSttsCd,
        OffsetDateTime sbmsnDt,
        RecruitmentApplicantResponse member,
        QuestionCompositionContent qitemCpstCn,
        ResponseContent rspnsCn,
        Long eventPtcpId,
        EventParticipantStatus ptcpSttsCd,
        Long prevFormRspnsId,
        Long nextFormRspnsId) {

    /**
     * @param participant null이면 아직 선발되지 않은 지원자다
     */
    public static RecruitmentApplicationDetailResponse of(
            FormResponseDetailResponse application,
            QuestionCompositionContent questionComposition,
            EventParticipantEntity participant) {
        return new RecruitmentApplicationDetailResponse(
                application.formRspnsId(),
                application.rspnsSeq(),
                application.rspnsSttsCd(),
                application.sbmsnDt(),
                RecruitmentApplicantResponse.from(application.member()),
                questionComposition,
                application.rspnsCn(),
                participant == null ? null : participant.getId(),
                participant == null ? null : participant.getStatus(),
                application.prevFormRspnsId(),
                application.nextFormRspnsId());
    }
}
