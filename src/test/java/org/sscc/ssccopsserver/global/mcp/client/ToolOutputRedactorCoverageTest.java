package org.sscc.ssccopsserver.global.mcp.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/*
 * {@link ToolOutputRedactor}가 **정말로** 빠뜨릴 자리가 없는가 (ssccops#365 W3 · #567).
 *
 * 그 클래스의 주석은 «어느 도구가 어느 record를 돌려주든 같은 자리를 지나므로 빠뜨릴 도구가
 * 없다»고 적는다. 지나가는 것은 맞지만 **거르는 기준이 필드 이름**이라, 같은 값을 다른 이름으로
 * 부르는 record가 있으면 그 자리는 그냥 통과한다. 실제로 그랬다 — 지우는 이름은
 * `phoneNumber`·`email`·`studentNumber` 셋뿐인데 이 저장소에는 같은 값을 `stdntNo`·`telno`·`eml`
 * 로 쓰는 DTO가 일곱 개 있었다(`ResponseMemberSummary`·`ResponseMemberDetail`·`RoleDetailResponse`
 * 등). 도구가 아직 그 record를 돌려주지 않아 새지 않았을 뿐, **모집 지원 목록·응답 심사·회원
 * 도구가 열리는 순간 새는 자리**였다.
 *
 * 그래서 규칙을 문장이 아니라 **전수 대조**로 옮긴다 — `file_rfrnc`의 CHECK 제약이 enum만 자라고
 * 제약은 그대로였던 사고(server#443)를 테스트로 옮긴 것과 같은 처방이다.
 *
 * 하는 일: `domain/…/dto` 아래 모든 자바 원본에서 주석·문자열을 걷어낸 뒤 «개인정보로 읽히는 이름»을
 * 찾아, 하나라도 {@link ToolOutputRedactor#REDACTED_FIELDS}에 없으면 실패한다.
 *
 * **이 테스트가 깨지면 둘 중 하나다.** 새 이름으로 같은 값을 부르는 record가 생겼거나(그러면
 * 그 이름을 지우는 목록에 더한다), 개인정보가 아닌데 이름이 걸린 것이다(그러면 아래
 * {@code NOT_PERSONAL}에 근거와 함께 적는다 — 비우고 지나가지 말 것).
 */
class ToolOutputRedactorCoverageTest {

    /** DTO 원본이 있는 자리. 모듈 루트 기준 상대 경로다 */
    private static final Path DTO_ROOT =
            Path.of("src", "main", "java", "org", "sscc", "ssccopsserver", "domain");

    /**
     * 개인정보로 읽히는 이름 — 전화·이메일·학번의 모든 표기.
     *
     * <p>이 저장소는 한 값을 두 벌로 부른다(회원 도메인은 영어 전체 이름, 나머지는 데이터사전 약어). 어느 쪽이 맞다고 정하는 것은 이 테스트의 일이 아니고, **둘
     * 다 지워지는지**만 본다.
     */
    private static final List<String> PERSONAL_NAMES =
            List.of(
                    "phoneNumber",
                    "phoneNo",
                    "telno",
                    "mobileNo",
                    "email",
                    "eml",
                    "studentNumber",
                    "stdntNo");

    /** 원본에서 식별자를 통째로 집는다. 개인정보인지는 {@link #personalFieldName} 이 가른다 */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9]*");

    /**
     * 이름이 걸리지만 개인정보가 아닌 것 — **근거 없이 더하지 말 것.**
     *
     * <p>지금은 비어 있다. 비어 있는 것 자체가 사실이다 — 걸린 이름은 전부 진짜였다.
     */
    private static final List<String> NOT_PERSONAL = List.of();

    @Test
    @DisplayName("도메인 DTO가 쓰는 개인정보 이름이 전부 지우는 목록에 들어 있다")
    void everyPersonalFieldNameIsRedacted() throws IOException {
        List<String> misses = new ArrayList<>();

        try (Stream<Path> files = Files.walk(DTO_ROOT)) {
            for (Path file :
                    files.filter(p -> p.getFileName().toString().endsWith(".java"))
                            .filter(p -> p.getParent().getFileName().toString().equals("dto"))
                            .toList()) {
                String source =
                        stripCommentsAndStrings(Files.readString(file, StandardCharsets.UTF_8));
                Matcher matcher = IDENTIFIER.matcher(source);
                while (matcher.find()) {
                    String name = personalFieldName(matcher.group());
                    if (name == null
                            || ToolOutputRedactor.REDACTED_FIELDS.contains(name)
                            || NOT_PERSONAL.contains(name)) {
                        continue;
                    }
                    String miss = file.getFileName() + " · " + name;
                    if (!misses.contains(miss)) {
                        misses.add(miss);
                    }
                }
            }
        }

        assertThat(misses)
                .describedAs(
                        """
                        도구 출력에서 지워지지 않는 개인정보 이름이 있다.
                        ToolOutputRedactor.REDACTED_FIELDS 에 더하거나, 개인정보가 아니면
                        이 테스트의 NOT_PERSONAL 에 근거와 함께 적는다.""")
                .isEmpty();
    }

    @Test
    @DisplayName("지우는 목록은 두 표기를 모두 든다 — 한쪽만 들면 그 자리가 곧 구멍이다")
    void redactedFieldsCoverBothSpellings() {
        assertThat(ToolOutputRedactor.REDACTED_FIELDS)
                .contains("phoneNumber", "email", "studentNumber")
                .contains("telno", "eml", "stdntNo");
    }

    /**
     * 식별자가 개인정보 **필드 이름**이면 그 이름을, 아니면 {@code null}.
     *
     * <p>세 가지를 가른다.
     *
     * <ul>
     *   <li>게터·세터는 필드가 아니다 — {@code getStudentNumber} 는 엔티티에서 값을 꺼내는 호출이고 JSON 키는 그 record 의 {@code
     *       studentNumber} 다. 접두사를 떼고 첫 글자를 내려 **필드 이름으로 되돌린 뒤** 판정한다(그래야 이미 지워지는 이름을 두 번 세지 않는다).
     *   <li>합성어도 필드다 — {@code applicantEmail} 같은 이름이 생기면 걸러지지 않으므로 **여기서 잡아야 한다.** 지우는 쪽이 키 이름 정확
     *       일치라 «비슷한 이름»은 그냥 통과한다.
     *   <li>우연한 부분 문자열은 아니다 — {@code eml} 은 낱말로 서거나 {@code …Eml} 로 붙을 때만 본다. 아무 데나 낀 세 글자를 잡으면 거짓
     *       실패가 진짜 실패를 덮는다.
     * </ul>
     */
    private static String personalFieldName(String identifier) {
        String field = identifier;
        for (String prefix : List.of("get", "set", "is")) {
            if (field.length() > prefix.length()
                    && field.startsWith(prefix)
                    && Character.isUpperCase(field.charAt(prefix.length()))) {
                field = field.substring(prefix.length());
                break;
            }
        }
        field = Character.toLowerCase(field.charAt(0)) + field.substring(1);

        for (String personal : PERSONAL_NAMES) {
            String suffix = Character.toUpperCase(personal.charAt(0)) + personal.substring(1);
            if (field.equals(personal) || field.endsWith(suffix)) {
                return field;
            }
        }
        return null;
    }

    /**
     * 주석과 문자열 리터럴을 걷어낸다.
     *
     * <p>걷지 않으면 «이메일은 싣지 않는다» 같은 **주석 문장이 필드로 잡힌다.** 이 저장소는 주석이 길어서 그대로 두면 거짓 실패가 진짜 실패를 덮는다.
     */
    private static String stripCommentsAndStrings(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ") // 블록 주석
                .replaceAll("(?m)//.*$", " ") // 줄 주석
                .replaceAll("(?s)\"\"\".*?\"\"\"", " ") // 텍스트 블록
                .replaceAll("\"(\\\\.|[^\"\\\\])*\"", " "); // 문자열 리터럴
    }
}
