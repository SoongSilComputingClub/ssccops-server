package org.sscc.ssccopsserver.global.mcp.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class ToolOutputRedactorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolOutputRedactor redactor = new ToolOutputRedactor();

    @Test
    @DisplayName("깊이와 무관하게 연락처·이메일·학번을 지우고 이름은 남긴다")
    void removesPiiAtAnyDepth() throws Exception {
        JsonNode node =
                mapper.readTree(
                        """
                        {
                          "subWorkId": 1,
                          "owner": {"memberId": 3, "name": "김도현", "phoneNumber": "010-1234-5678",
                                    "email": "kim@sscc.org", "studentNumber": "20200001"},
                          "collaborators": [
                            {"memberId": 4, "name": "이서연", "phoneNumber": "010-9999-0000", "email": "lee@sscc.org"}
                          ],
                          "nested": {"deeper": {"email": "x@y.z", "keep": "값"}}
                        }
                        """);

        String out = redactor.redact(node).toString();

        assertThat(out)
                .doesNotContain("phoneNumber", "email", "studentNumber", "010-", "@sscc.org");
        assertThat(out).contains("김도현", "이서연", "\"keep\":\"값\"", "\"memberId\":3");
    }

    @Test
    @DisplayName("데이터사전 약어 표기(stdntNo·telno·eml)도 같이 지운다")
    void removesAbbreviatedSpellingsToo() throws Exception {
        /*
         * 이 저장소는 한 값을 두 벌로 부른다 — 회원 도메인 DTO 는 영어 전체 이름을, 나머지는
         * 데이터사전 약어를 쓴다(`ResponseMemberSummary.stdntNo` · `ResponseMemberDetail.telno`).
         * 약어 쪽을 모르던 동안 위 주석의 «빠뜨릴 도구가 없다»가 그 record 들에는 성립하지
         * 않았다(#567). 이름 전수 대조는 `ToolOutputRedactorCoverageTest` 가 하고, **실제로
         * 지워지는지**는 여기서 본다.
         */
        JsonNode node =
                mapper.readTree(
                        """
                        {
                          "formRspnsId": 7,
                          "member": {"mbrId": 3, "mbrNm": "김도현", "stdntNo": "20200001",
                                     "telno": "010-1234-5678", "eml": "kim@sscc.org"},
                          "nested": {"deeper": {"stdntNo": "20200002", "keep": "값"}}
                        }
                        """);

        String out = redactor.redact(node).toString();

        assertThat(out)
                .doesNotContain(
                        "stdntNo", "telno", "eml", "20200001", "20200002", "010-", "@sscc.org");
        assertThat(out).contains("김도현", "\"keep\":\"값\"", "\"mbrId\":3");
    }

    @Test
    @DisplayName("배열 루트와 null도 그대로 지나간다")
    void handlesArraysAndNull() throws Exception {
        JsonNode array = mapper.readTree("[{\"email\":\"a@b.c\",\"id\":1},{\"id\":2}]");

        assertThat(redactor.redact(array).toString()).isEqualTo("[{\"id\":1},{\"id\":2}]");
        assertThat(redactor.redact(null)).isNull();
    }
}
