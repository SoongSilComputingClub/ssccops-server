package org.sscc.ssccopsserver.domain.form.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
import org.sscc.ssccopsserver.domain.form.entity.ResponseContent;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseHistoryRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleClassificationRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.MemberRoleFixture;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

import com.fasterxml.jackson.databind.ObjectMapper;

/*
 * 폼 소프트 삭제·되살리기(#329) 통합 검증.
 *
 * 확인하는 것은 다섯이다 — 지운 폼이 목록에서 빠지는가 · 되살아나는가 · 공개 링크가 어떻게
 * 답하는가 · 본인 응답 조회가 어떻게 되는가 · **응답이 있어도 지워지는가**. 마지막 하나가
 * ssccops#261이 확정한 결정이며, 그것을 감당 가능하게 만드는 것이 되살리기라 두 항목은 함께
 * 봐야 한다.
 *
 * 인증 주체 하나가 운영자이자 응답자다. 폼을 지우는 사람과 그 폼에 응답한 사람을 나누면 토큰이
 * 둘 필요한데, 여기서 보려는 것은 "지운 뒤 그 응답이 어떻게 보이는가"이고 그 응답이 누구 것인지는
 * 판정에 들어가지 않는다(응답 조회는 언제나 인증 주체 본인의 행만 본다).
 *
 * 실패를 기대하는 요청은 각 테스트의 마지막에 몰아 둔다 — 서비스가 던진 예외가 참여 트랜잭션을
 * rollback-only로 표시하므로, 그 뒤에 성공을 기대하는 요청을 두면 무엇을 확인하는 테스트인지가
 * 흐려진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestJwtDecoderConfig.class, FormSoftDeleteTest.FixedClockConfig.class})
@Transactional
class FormSoftDeleteTest {

    private static final UUID AUTH_USER_ID = UUID.randomUUID();

    /** 고정 기준 시각 (2026-03-15 00:00 KST). 삭제 시각(delDt)이 주입된 Clock에서 오는지 본다 */
    private static final Instant NOW = Instant.parse("2026-03-14T15:00:00Z");

    /** 위 시각을 서비스 표준 시간대(AP-12)로 표기한 값 */
    private static final String NOW_IN_SERVICE_ZONE = "2026-03-15T00:00:00+09:00";

    private static final String SAMPLE_COMPOSITION =
            """
            {
              "pages": [{"pageTtl": "기본 정보", "pageDescCn": "지원자 정보를 입력해주세요."}],
              "qitems": [
                {
                  "qitemId": "q1", "qitemLblNm": "이름", "qitemTypeCd": "SHORT_TEXT",
                  "reqYn": true, "pageSeq": 0, "optionList": []
                }
              ]
            }
            """;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private FormRepository formRepository;
    @Autowired private FormResponseHistoryRepository formResponseHistoryRepository;

    private MemberEntity actor;

    @BeforeEach
    void setUp() {
        actor = saveMember(AUTH_USER_ID, "20260001", "이서연", "actor@sscc.org");

        // 삭제·복구는 FORM_WRITE, 목록·휴지통은 FORM_READ다. 국장(OPERATOR)이 둘 다에 닿는다
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                actor,
                MemberRoleFixture.DIRECTOR);
    }

    /* ── 목록에서 빠진다 · 휴지통에 담긴다 ─────────────────── */

    /*
     * 이 이슈의 본래 요구다 — 운영진이 테스트로 만든 폼을 목록에서 치울 방법이 없던 것이 문제였다.
     * 지운 폼은 목록 질의에서 통째로 빠지고 휴지통 목록에만 남는다.
     */
    @Test
    void deletedFormLeavesTheListAndLandsInTrash() throws Exception {
        Long kept = saveForm("남는 폼", FormStatus.OPEN);
        Long removed = saveForm("지울 폼", FormStatus.DRAFT);

        mockMvc.perform(authenticatedDelete("/v1/forms/" + removed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(authenticatedGet("/v1/forms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].formId").value(kept))
                .andExpect(jsonPath("$.data[0].delDt").isEmpty());

        mockMvc.perform(authenticatedGet("/v1/forms/deleted"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].formId").value(removed))
                .andExpect(jsonPath("$.data[0].formTtlNm").value("지울 폼"))
                // 삭제 시각은 주입된 Clock에서 온다 — 시스템 시각이면 이 비교가 성립하지 않는다
                .andExpect(jsonPath("$.data[0].delDt").value(NOW_IN_SERVICE_ZONE));

        assertThat(formRepository.findById(removed)).isPresent();
    }

    /*
     * 접수 상태 필터를 걸어도 지운 폼은 오지 않는다. 삭제 여부가 receiptStatus와 같은 축이었다면
     * '전체'나 'DRAFT'로 조회할 때 섞여 들어왔을 자리다 (#325의 필터 위에 얹힌 조건이다).
     */
    @Test
    void deletedFormIsExcludedFromEveryReceiptStatusFilter() throws Exception {
        Long removed = saveForm("지울 폼", FormStatus.DRAFT);

        mockMvc.perform(authenticatedDelete("/v1/forms/" + removed)).andExpect(status().isOk());

        mockMvc.perform(authenticatedGet("/v1/forms?receiptStatus=DRAFT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
        mockMvc.perform(authenticatedGet("/v1/forms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    /* ── 되살린다 ──────────────────────────────────────────── */

    /*
     * **되살리기가 이 작업의 필수 항목이다** (ssccops#261). 되돌릴 수 없으면 응답이 있는 폼을
     * 지우는 결정이 하드 삭제와 같아지고, 그때는 신청자의 기록이 영영 닫힌다.
     *
     * 접수 상태는 지울 때 그대로 남으므로 접수 중이던 폼은 되살아나서도 접수 중이다.
     */
    @Test
    void restoredFormComesBackExactlyAsItWas() throws Exception {
        Long formId = saveForm("되살릴 폼", FormStatus.OPEN);

        mockMvc.perform(authenticatedDelete("/v1/forms/" + formId)).andExpect(status().isOk());
        mockMvc.perform(authenticatedPost("/v1/forms/" + formId + "/restore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(authenticatedGet("/v1/forms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].formId").value(formId))
                .andExpect(jsonPath("$.data[0].formSttsCd").value("OPEN"))
                .andExpect(jsonPath("$.data[0].receiptStatus").value("ACCEPTING"))
                .andExpect(jsonPath("$.data[0].delDt").isEmpty());

        mockMvc.perform(authenticatedGet("/v1/forms/deleted"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    /* ── 응답이 있어도 지운다 ─────────────────────────────── */

    /*
     * **확정된 결정이다 — 응답 수를 보지 않는다** (ssccops#261). 응답이 있으면 못 지우게 하는 안은
     * 기각됐다: 테스트 폼에 응답이 하나만 들어와도 영영 목록에 남고, 그것이 지금 고치는 증상이다.
     *
     * 응답 행 자체는 지우지 않는다 — 남아 있는 것이 되살리기가 성립하는 조건이다.
     */
    @Test
    void formWithSubmittedResponsesIsDeletedAnyway() throws Exception {
        Long formId = saveForm("응답이 들어온 폼", FormStatus.OPEN);
        saveSubmittedResponse(formId);

        mockMvc.perform(authenticatedGet("/v1/forms"))
                .andExpect(jsonPath("$.data[0].responseCount").value(1));

        mockMvc.perform(authenticatedDelete("/v1/forms/" + formId)).andExpect(status().isOk());

        mockMvc.perform(authenticatedGet("/v1/forms/deleted"))
                .andExpect(status().isOk())
                // 되살릴지 정하는 사람이 알아야 하는 것이 "이 폼에 신청이 몇 건 있었는가"다
                .andExpect(jsonPath("$.data[0].responseCount").value(1));

        assertThat(formResponseHistoryRepository.count()).isEqualTo(1);
    }

    /* ── 공개 링크 ─────────────────────────────────────────── */

    /*
     * 공개 링크(/f/{formId})가 무엇을 받는지. **없는 폼과 같은 404다.**
     *
     * 익명 메타 조회는 링크만 가진 크롤러가 부르는 자리라 코드를 나누면 그 번호의 폼이 있었다는
     * 사실이 그대로 새어 나가고, 메신저는 카드를 한 번 캐싱하면 갱신하지 않아 "삭제된 폼"이라
     * 말하는 카드가 되살린 뒤에도 방에 남는다.
     *
     * 응답자용 조회도 409 FORM_NOT_ACCEPTING이 아니라 404다 — 409는 폼이 있다는 것을 전제하는
     * 코드라 존재가 드러난다.
     */
    @Test
    void deletedFormAnswersPublicLinkWithTheSameNotFoundAsAMissingForm() throws Exception {
        Long formId = saveForm("공개된 폼", FormStatus.OPEN);

        mockMvc.perform(authenticatedDelete("/v1/forms/" + formId)).andExpect(status().isOk());

        mockMvc.perform(get("/public/v1/forms/" + formId + "/meta"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(authenticatedGet("/v1/forms/" + formId + "/public"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /* ── 본인 응답 조회 ───────────────────────────────────── */

    /*
     * **이 결정이 치르는 대가다** (ssccops#261). 폼이 지워지면 제목·문항이 없어 화면이 성립하지
     * 않으므로 상세는 404이고, 목록에서도 그 줄이 빠진다 — 줄만 남으면 눌렀을 때 갈 곳이 없다.
     *
     * 목록에서 사라지는 것과 상세가 404가 되는 것은 다른 일이라 둘 다 본다.
     */
    @Test
    void deletedFormClosesTheRespondentsOwnResponseLookup() throws Exception {
        Long formId = saveForm("신청 폼", FormStatus.OPEN);
        Long responseId = saveSubmittedResponse(formId);

        mockMvc.perform(authenticatedGet("/v1/forms/responses/mine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        mockMvc.perform(authenticatedDelete("/v1/forms/" + formId)).andExpect(status().isOk());

        // 폼을 가로지르는 목록은 200이되 그 줄이 빠진다
        mockMvc.perform(authenticatedGet("/v1/forms/responses/mine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        mockMvc.perform(authenticatedGet("/v1/forms/" + formId + "/responses/mine"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(authenticatedGet("/v1/forms/" + formId + "/responses/mine/" + responseId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /*
     * 되살리면 그 대가가 그대로 되돌아온다 — 응답을 지우지 않기 때문이다. 이것이 없으면
     * 소프트 삭제라고 부를 근거가 없다.
     */
    @Test
    void restoringTheFormReopensTheRespondentsOwnResponseLookup() throws Exception {
        Long formId = saveForm("신청 폼", FormStatus.OPEN);
        Long responseId = saveSubmittedResponse(formId);

        mockMvc.perform(authenticatedDelete("/v1/forms/" + formId)).andExpect(status().isOk());
        mockMvc.perform(authenticatedPost("/v1/forms/" + formId + "/restore"))
                .andExpect(status().isOk());

        mockMvc.perform(authenticatedGet("/v1/forms/responses/mine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].formRspnsId").value(responseId));
        mockMvc.perform(authenticatedGet("/v1/forms/" + formId + "/responses/mine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(authenticatedGet("/v1/forms/" + formId + "/responses/mine/" + responseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.formRspnsId").value(responseId));
    }

    /* ── 운영자 경로도 함께 닫힌다 ────────────────────────── */

    // 상세·응답 목록도 같은 404다. 지워진 폼의 응답을 계속 심사할 수 있으면 "지웠다"의 뜻이 화면마다 달라진다
    @Test
    void deletedFormIsNotFoundOnOperatorPathsEither() throws Exception {
        Long formId = saveForm("심사 중이던 폼", FormStatus.OPEN);
        saveSubmittedResponse(formId);

        mockMvc.perform(authenticatedDelete("/v1/forms/" + formId)).andExpect(status().isOk());

        mockMvc.perform(authenticatedGet("/v1/forms/" + formId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /* ── 거절 ─────────────────────────────────────────────── */

    /*
     * 시스템 폼은 코드가 sys_form_cd로 직접 가리키는 폼이라 지워지는 순간 그 코드를 읽는 기능이
     * 통째로 무너진다. 판정은 #140이 미리 세워 둔 FormEntity.requireDeletable을 그대로 부른다.
     */
    @Test
    void systemFormCannotBeDeleted() throws Exception {
        Long formId = saveForm("잠긴 폼", FormStatus.OPEN);
        formRepository
                .findById(formId)
                .orElseThrow()
                .designateAsSystemForm("TEST_SOFT_DELETE_LOCK");
        formRepository.flush();

        mockMvc.perform(authenticatedDelete("/v1/forms/" + formId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SYSTEM_FORM_IMMUTABLE"));
    }

    /*
     * 이미 지운 폼을 또 지우는 것은 404가 아니라 409다 — 휴지통을 보고 있는 운영진에게
     * "없는 폼"과 "이미 지운 폼"은 다음에 할 일이 다르다(앞은 새로고침, 뒤는 아무것도 아니다).
     * 조회 계열이 둘을 같은 404로 묶는 것과 일부러 갈린다.
     */
    @Test
    void deletingTwiceIsConflict() throws Exception {
        Long formId = saveForm("지울 폼", FormStatus.DRAFT);

        mockMvc.perform(authenticatedDelete("/v1/forms/" + formId)).andExpect(status().isOk());
        mockMvc.perform(authenticatedDelete("/v1/forms/" + formId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_DELETED"));
    }

    // 대칭. 조용히 통과시키면 두 운영진이 같은 휴지통을 열고 있을 때 뒤에 누른 쪽이 자기가 되살렸다고 믿는다
    @Test
    void restoringAFormThatWasNotDeletedIsConflict() throws Exception {
        Long formId = saveForm("멀쩡한 폼", FormStatus.DRAFT);

        mockMvc.perform(authenticatedPost("/v1/forms/" + formId + "/restore"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_DELETED"));
    }

    // 없는 폼은 삭제·복구 양쪽에서 404다
    @Test
    void deletingAMissingFormIsNotFound() throws Exception {
        mockMvc.perform(authenticatedDelete("/v1/forms/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /* ── 도우미 ───────────────────────────────────────────── */

    private Long saveForm(String title, FormStatus status) throws Exception {
        QuestionCompositionContent content =
                objectMapper.readValue(SAMPLE_COMPOSITION, QuestionCompositionContent.class);
        return formRepository
                .saveAndFlush(FormEntity.create(actor, title, content, null, null, status))
                .getId();
    }

    /*
     * 제출된 응답 한 건. 제출 API를 쓰지 않는 것은 이 클래스가 보려는 것이 제출 규칙이 아니라
     * "응답이 있는 폼을 지웠을 때 그 응답이 어떻게 보이는가"이기 때문이다.
     */
    private Long saveSubmittedResponse(Long formId) {
        FormEntity form = formRepository.findById(formId).orElseThrow();
        return formResponseHistoryRepository
                .saveAndFlush(
                        FormResponseHistoryEntity.createSubmitted(
                                form, actor, ResponseContent.of(Map.of("q1", "이서연")), NOW))
                .getId();
    }

    private MemberEntity saveMember(
            UUID authUserId, String studentNumber, String name, String email) {
        return MemberFixture.save(
                memberRepository,
                memberGradeRepository,
                memberStatusRepository,
                authUserId,
                studentNumber,
                name,
                email);
    }

    private MockHttpServletRequestBuilder authenticatedGet(String path) {
        return get(path).header("Authorization", "Bearer " + AUTH_USER_ID);
    }

    private MockHttpServletRequestBuilder authenticatedPost(String path) {
        return post(path).header("Authorization", "Bearer " + AUTH_USER_ID);
    }

    private MockHttpServletRequestBuilder authenticatedDelete(String path) {
        return delete(path).header("Authorization", "Bearer " + AUTH_USER_ID);
    }

    @TestConfiguration
    static class FixedClockConfig {

        /*
         * 삭제 시각(del_dt)이 주입된 Clock에서 오는지 확인해야 하므로 시각을 고정한다.
         * ClockConfig가 정의한 clock 빈과 이름이 겹치지 않게 다른 이름으로 둔다 (FormControllerTest 선례).
         */
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));
        }
    }
}
