package org.sscc.ssccopsserver.domain.form.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/*
 * 413 메시지의 «남은 용량은 7.7MB입니다» (server#685). 0.1MB 단위에서 **내림**한다 — 부풀려 말하면 작성자가
 * 그 크기로 다시 골라도 413이 난다. 경계는 7.7MB = 8,074,035.2바이트라 1바이트 차이로 갈린다.
 */
class ProposalAttachmentCapacityMessageTest {

    @Test
    @DisplayName("남은 용량은 내림해 말한다 — 7.7MB에 0.2바이트 모자라면 7.6MB")
    void roundsDownToATenthOfAMegabyte() {
        assertThat(ProposalAttachmentServiceImpl.megabytes(8_074_036L)).isEqualTo("7.7MB");
        assertThat(ProposalAttachmentServiceImpl.megabytes(8_074_035L)).isEqualTo("7.6MB");
    }

    @Test
    @DisplayName("소수가 0이면 떼어 쓴다 — 상한은 «20MB», 다 찼으면 «0MB»")
    void dropsAZeroFraction() {
        assertThat(
                        ProposalAttachmentServiceImpl.megabytes(
                                ProposalAttachmentServiceImpl.MAX_TOTAL_SIZE_BYTES))
                .isEqualTo("20MB");
        assertThat(ProposalAttachmentServiceImpl.megabytes(0)).isEqualTo("0MB");
        assertThat(ProposalAttachmentServiceImpl.megabytes(100_000)).isEqualTo("0MB");
    }
}
