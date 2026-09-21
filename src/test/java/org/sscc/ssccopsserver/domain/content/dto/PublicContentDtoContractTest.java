package org.sscc.ssccopsserver.domain.content.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.sscc.ssccopsserver.domain.form.dto.PublicOpenFormResponse;
import org.sscc.ssccopsserver.domain.form.dto.PublicSystemFormMetaResponse;

/*
 * 익명 응답 record의 필드 대조 (ssccops#381 · ADR-0038 «응답 DTO는 어드민 DTO와 다른 record로
 * 두고, 테스트가 필드 이름을 대조해 개인 필드·이력 필드가 없음을 못 박는다»).
 *
 * 금지 목록은 두 겹이다 — **회원 개인 필드**(ToolOutputRedactor와 같은 셋 + 이름·수정자·변경자)와
 * **운영 필드**(이력·운영 타임스탬프·수정자 id). 컴포넌트 이름을 소문자로 내려 부분 일치로 보므로
 * `mdfcnMbrNm`·`chgMbrId`·`histories` 같은 변형도 걸린다. 공개 record가 늘면 아래 목록에 더한다 —
 * SecurityConfig의 /public/v1 주석이 이 테스트를 가리킨다.
 */
class PublicContentDtoContractTest {

    private static final List<Class<?>> PUBLIC_RECORDS =
            List.of(
                    PublicContentPageResponse.class,
                    PublicContentPageSummaryResponse.class,
                    PublicContentPostSummaryResponse.class,
                    PublicContentPostDetailResponse.class,
                    ContentImageResponse.class,
                    PublicOpenFormResponse.class,
                    PublicSystemFormMetaResponse.class);

    /** 익명 응답에 절대 실리지 않는 것 — 소문자 부분 일치 */
    private static final Set<String> DENY_LIST =
            Set.of(
                    "mdfcnmbr",
                    "chgmbr",
                    "creatrmbr",
                    "mbrid",
                    "memberid",
                    "history",
                    "hstry",
                    "email",
                    "phone",
                    "studentnumber",
                    "stdntno",
                    "name",
                    "regdt",
                    "mdfcndt",
                    "crtdt",
                    "authuserid");

    @Test
    @DisplayName("익명 record 어디에도 수정자·이력·회원 개인 필드가 없다")
    void publicRecordsCarryNoPersonalOrOperationalFields() {
        for (Class<?> record : PUBLIC_RECORDS) {
            assertThat(record.isRecord()).as("%s는 record여야 한다", record.getSimpleName()).isTrue();
            List<String> names =
                    Arrays.stream(record.getRecordComponents())
                            .map(RecordComponent::getName)
                            .map(name -> name.toLowerCase(Locale.ROOT))
                            .toList();
            for (String name : names) {
                assertThat(DENY_LIST)
                        .as("%s.%s — 익명 응답에 실리면 안 되는 필드", record.getSimpleName(), name)
                        .noneMatch(name::contains);
            }
        }
    }

    /*
     * ADR-0038의 표를 그대로 못 박는다 — 실리는 것이 «이것뿐»임을 정확히 대조해야 나중에 필드가
     * 슬쩍 늘어도 이 테스트가 말한다. 늘리는 것이 맞으면 ADR을 먼저 고친다.
     */
    @Test
    @DisplayName("페이지 응답은 slug·제목·본문·게시일 넷뿐이다")
    void pageResponseIsExactlyFourFields() {
        assertThat(componentNames(PublicContentPageResponse.class))
                .containsExactlyInAnyOrder("slug", "ttl", "mtxt", "pubDt");
    }

    @Test
    @DisplayName("포스트 상세 응답은 ADR-0038의 표 그대로다")
    void postDetailResponseMatchesTheAdrTable() {
        assertThat(componentNames(PublicContentPostDetailResponse.class))
                .containsExactlyInAnyOrder(
                        "slug",
                        "cntntClsfCd",
                        "ttl",
                        "smry",
                        "mtxt",
                        "actvYmd",
                        "eventId",
                        "coverFileId",
                        "coverImageUrl",
                        "gallery",
                        "pubDt");
    }

    @Test
    @DisplayName("접수 중 폼 응답은 폼 키·제목·마감 셋뿐이다")
    void openFormResponseIsExactlyThreeFields() {
        assertThat(componentNames(PublicOpenFormResponse.class))
                .containsExactlyInAnyOrder("formKey", "formTtlNm", "rcptEndDt");
    }

    /*
     * 지정 시스템 폼 메타 (#520 · ADR-0044). 페이지 재료라 접수 상태·기간을 싣는 것이 OG 카드용
     * /forms/{id}/meta와 갈리는 지점이고, 숫자 id·문항·안내 문구는 없다.
     */
    @Test
    @DisplayName("지정 시스템 폼 메타는 폼 키·제목·접수 상태·기간 다섯뿐이다")
    void systemFormMetaResponseIsExactlyFiveFields() {
        assertThat(componentNames(PublicSystemFormMetaResponse.class))
                .containsExactlyInAnyOrder(
                        "formKey", "formTtlNm", "receiptStatus", "rcptBgngDt", "rcptEndDt");
    }

    private static List<String> componentNames(Class<?> record) {
        return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).toList();
    }
}
