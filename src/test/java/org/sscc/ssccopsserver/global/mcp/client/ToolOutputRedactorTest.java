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
    @DisplayName("배열 루트와 null도 그대로 지나간다")
    void handlesArraysAndNull() throws Exception {
        JsonNode array = mapper.readTree("[{\"email\":\"a@b.c\",\"id\":1},{\"id\":2}]");

        assertThat(redactor.redact(array).toString()).isEqualTo("[{\"id\":1},{\"id\":2}]");
        assertThat(redactor.redact(null)).isNull();
    }
}
