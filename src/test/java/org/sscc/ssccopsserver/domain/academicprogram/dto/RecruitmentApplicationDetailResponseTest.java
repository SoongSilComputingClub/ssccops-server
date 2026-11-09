package org.sscc.ssccopsserver.domain.academicprogram.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/*
 * 리더용 지원서 상세의 모양이 고정돼 있는지 (ssccops#625).
 *
 * RecruitmentApplicationResponseTest와 **방향이 반대다.** 그쪽은 폼 응답 요약의 필드가 목록에서 빠지지
 * 않는지를 보고, 이쪽은 운영자용 상세(FormResponseDetailResponse)에서 필드를 **고른** 모양이 그대로인지를
 * 본다 — 연락처(telno) · 검토 이력(reviewHistories) · 제출 회차(sbmsnSeq)가 들어오면 리더에게 새는 것이고,
 * 이름이 바뀌면 웹(ssccops-web#826)이 조용히 값을 잃는다. 필드를 더하거나 바꾸려면 이 목록과 웹 타입을
 * 함께 고친다.
 */
class RecruitmentApplicationDetailResponseTest {

    @Test
    void detailCarriesExactlyTheAgreedFields() {
        assertThat(componentNames(RecruitmentApplicationDetailResponse.class))
                .containsExactly(
                        "formRspnsId",
                        "rspnsSeq",
                        "rspnsSttsCd",
                        "sbmsnDt",
                        "member",
                        "qitemCpstCn",
                        "rspnsCn",
                        "eventPtcpId",
                        "ptcpSttsCd",
                        "prevFormRspnsId",
                        "nextFormRspnsId");
    }

    // 연락처는 null이 아니라 자리 자체가 없다 — 리더가 MEMBER_MANAGE를 겸해도 실을 곳이 없다
    @Test
    void applicantHasNoContactField() {
        assertThat(componentNames(RecruitmentApplicantResponse.class))
                .containsExactly(
                        "mbrId",
                        "mbrNm",
                        "stdntNo",
                        "scsbjtNm",
                        "mbrGrdCd",
                        "mbrSttsCd",
                        "genNo",
                        "scyrNo")
                .doesNotContain("telno");
    }

    private static List<String> componentNames(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }
}
