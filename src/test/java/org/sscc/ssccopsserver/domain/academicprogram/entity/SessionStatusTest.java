package org.sscc.ssccopsserver.domain.academicprogram.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/*
 * isEditable(#134)의 "상태" 절반을 못 박는다. 나머지 절반(스터디장 본인 여부)은
 * AcademicProgramControllerTest가 API로 확인하지만, 상태 쪽은 Session 엔티티가 아직 없어
 * (#135) API로는 NOT_SUBMITTED 한 값에만 닿을 수 있다 — 규칙 자체는 지금 정해졌으므로
 * 여기서 네 값을 전부 고정해 둔다.
 */
class SessionStatusTest {

    // 미제출은 신규 제출, 수정요청은 재제출 — 둘 다 스터디장이 기록을 쓸 수 있는 상태다
    @Test
    void notSubmittedAndRevisionRequestedAllowRecording() {
        assertThat(SessionStatus.NOT_SUBMITTED.allowsRecording()).isTrue();
        assertThat(SessionStatus.REVISION_REQUESTED.allowsRecording()).isTrue();
    }

    // 검토 대기(SUBMITTED)와 확정 이력(APPROVED)은 손대지 않는다
    @Test
    void submittedAndApprovedDoNotAllowRecording() {
        assertThat(SessionStatus.SUBMITTED.allowsRecording()).isFalse();
        assertThat(SessionStatus.APPROVED.allowsRecording()).isFalse();
    }

    /*
     * 출석 정정·인증사진(#137)은 APPROVED 하나만 잠근다 — allowsRecording과 답이 갈리는 값이
     * SUBMITTED이고, 그 차이가 이 API가 존재하는 이유다(기록 본문은 못 고쳐도 출석은 고친다).
     */
    @Test
    void onlyApprovedBlocksCorrection() {
        assertThat(SessionStatus.NOT_SUBMITTED.allowsCorrection()).isTrue();
        assertThat(SessionStatus.SUBMITTED.allowsCorrection()).isTrue();
        assertThat(SessionStatus.REVISION_REQUESTED.allowsCorrection()).isTrue();
        assertThat(SessionStatus.APPROVED.allowsCorrection()).isFalse();
    }
}
