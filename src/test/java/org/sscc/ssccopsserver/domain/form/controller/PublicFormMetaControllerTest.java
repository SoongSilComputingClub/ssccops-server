package org.sscc.ssccopsserver.domain.form.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.code.FormStatusAction;
import org.sscc.ssccopsserver.domain.form.code.QuestionItemType;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent.Page;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent.QuestionItem;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.global.apipayload.PublicCacheControl;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

/*
 * 익명 폼 메타 API(ssccops#201) 통합 검증.
 *
 * **모든 요청에 Authorization 헤더가 없다.** PublicEventControllerTest와 같은 이유다 —
 * permitAll이 실제로 걸려 있는지는 필터체인을 통째로 태워 봐야만 확인되고, 토큰을 붙이면
 * SecurityConfig의 규칙이 사라져도 테스트는 계속 초록으로 남는다.
 *
 * 표본은 리포지토리로 직접 만든다. 폼을 만드는 API는 FORM_WRITE를 요구하는데 그 권한을
 * 세우는 픽스처를 여기 두면 "익명"이라는 전제가 준비 단계에서 흐려진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@Transactional
class PublicFormMetaControllerTest {

    private static final Instant PAST = Instant.parse("2026-03-01T00:00:00Z");

    /** 접수 기간 안 판정을 시스템 시각으로 두어도 흔들리지 않게 충분히 먼 미래 */
    private static final Instant FUTURE = Instant.parse("2099-12-31T00:00:00Z");

    private static final String META = "/public/v1/forms/{formId}/meta";

    private static final String SYSTEM_META = "/public/v1/forms/system/{sysFormCd}/meta";

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private FormRepository formRepository;

    private MemberEntity creator;

    @BeforeEach
    void setUp() {
        creator =
                MemberFixture.save(
                        memberRepository,
                        memberGradeRepository,
                        memberStatusRepository,
                        UUID.randomUUID(),
                        "20260001",
                        "폼운영자",
                        "20260001@soongsil.ac.kr");
    }

    /* ── 연 적 있는 폼 ─────────────────────────────────────── */

    // 토큰 없이 200이고, 실리는 것은 제목과 첫 페이지 안내 문구뿐이다
    @Test
    void openFormMetaIsReadableByAnonymous() throws Exception {
        Long formId = saveForm("2026 신입 부원 모집", "SSCC와 함께할 신입 부원을 모집합니다.", FormStatus.OPEN, null);

        mockMvc.perform(get(META, formId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.formId").value(formId))
                .andExpect(jsonPath("$.data.formTtlNm").value("2026 신입 부원 모집"))
                .andExpect(jsonPath("$.data.pageDescCn").value("SSCC와 함께할 신입 부원을 모집합니다."))
                // 시간에 따라 변하는 값은 계약상 없다 — 카드가 굳으므로(ssccops#194 제약 ②)
                .andExpect(jsonPath("$.data.rcptBgngDt").doesNotExist())
                .andExpect(jsonPath("$.data.rcptEndDt").doesNotExist())
                .andExpect(jsonPath("$.data.receiptStatus").doesNotExist())
                .andExpect(jsonPath("$.data.qitemCpstCn").doesNotExist());
    }

    /*
     * 마감된 폼도 연 적 있는 폼이다. 이미 방에 뿌려진 링크의 카드가 마감과 함께 깨지면 안 된다.
     * 접수 종료 일시가 지난(EXPIRED) OPEN 폼도 같다.
     */
    @Test
    void closedAndExpiredFormsAreStillReadableByKey() throws Exception {
        Long closed = saveForm("마감된 모집", "지난 모집", FormStatus.OPEN, null);
        close(closed);
        Long expired = saveForm("기간 지난 모집", "기간이 지난 모집", FormStatus.OPEN, PAST);

        mockMvc.perform(get(META, keyOf(closed)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.formTtlNm").value("마감된 모집"))
                .andExpect(jsonPath("$.data.formKey").value(keyOf(closed).toString()));
        // 기간이 지났어도 상태가 OPEN이면 숫자로도 열린다 — 판정 재료는 상태뿐이다
        mockMvc.perform(get(META, expired))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.formTtlNm").value("기간 지난 모집"));
    }

    /*
     * 숫자 id는 지금 접수 중(OPEN)인 폼만 연다 (ADR-0036). 마감된 폼의 옛 숫자 링크는 카드가
     * 기본 문구로 떨어지지만, 숫자를 훑어 얻는 것이 «지금 링크가 돌고 있는 폼»을 넘지 않는다.
     */
    @Test
    void closedFormIsNotReadableByNumericId() throws Exception {
        Long closed = saveForm("마감된 모집", "지난 모집", FormStatus.OPEN, null);
        close(closed);

        mockMvc.perform(get(META, closed))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // 키로도 DRAFT는 404 — 키를 안다고 작성 중인 폼의 제목이 새지 않는다. 잘못된 모양도 같은 404
    @Test
    void draftByKeyAndMalformedRefAreNotFound() throws Exception {
        Long draft = saveForm("작성 중", "아직", FormStatus.DRAFT, null);

        mockMvc.perform(get(META, keyOf(draft))).andExpect(status().isNotFound());
        mockMvc.perform(get(META, UUID.randomUUID())).andExpect(status().isNotFound());
        mockMvc.perform(get(META, "not-a-ref")).andExpect(status().isNotFound());
    }

    // 안내 문구가 비어 있으면 null — 서버가 대체 문구를 만들어 내지 않는다
    @Test
    void blankDescriptionIsNull() throws Exception {
        Long formId = saveForm("안내 없는 폼", "   ", FormStatus.OPEN, null);

        mockMvc.perform(get(META, formId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.formTtlNm").value("안내 없는 폼"))
                .andExpect(jsonPath("$.data.pageDescCn").doesNotExist());
    }

    /* ── 존재를 감춘다 ─────────────────────────────────────── */

    /*
     * DRAFT 폼과 없는 폼은 상태 코드도 오류 코드도 같다. 나누면 formId를 훑는 것만으로
     * 운영진이 준비 중인 모집이 드러난다.
     */
    @Test
    void draftAndMissingFormsAreIndistinguishable() throws Exception {
        Long draft = saveForm("준비 중인 모집", "아직 공개 전", FormStatus.DRAFT, null);

        mockMvc.perform(get(META, draft))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.data").doesNotExist());
        mockMvc.perform(get(META, 999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    /* ── 지정 시스템 폼 메타 (#520 · ssccops#436 · ADR-0044) ─────────── */

    /*
     * 지정된 RECRUIT 폼은 토큰 없이 200이고 실리는 것은 키·제목·접수 상태·기간 다섯뿐이다. 숫자 id·
     * 문항·안내 문구는 없다. /forms/{id}/meta와 달리 접수 상태·기간을 싣는 것은 OG 카드가 아니라
     * 페이지 재료이기 때문이며, CDN 캐시 헤더가 함께 나간다.
     */
    @Test
    void designatedRecruitFormMetaIsReadableByAnonymous() throws Exception {
        Long formId = saveFormWithPeriod("2026-2 신입회원 모집", FormStatus.OPEN, PAST, FUTURE);
        designate(formId, "RECRUIT");

        mockMvc.perform(get(SYSTEM_META, "RECRUIT"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", PublicCacheControl.HEADER_VALUE))
                .andExpect(jsonPath("$.data.formKey").value(keyOf(formId).toString()))
                .andExpect(jsonPath("$.data.formTtlNm").value("2026-2 신입회원 모집"))
                .andExpect(jsonPath("$.data.receiptStatus").value("ACCEPTING"))
                .andExpect(jsonPath("$.data.rcptBgngDt").isNotEmpty())
                .andExpect(jsonPath("$.data.rcptEndDt").isNotEmpty())
                .andExpect(jsonPath("$.data.formId").doesNotExist())
                .andExpect(jsonPath("$.data.pageDescCn").doesNotExist())
                .andExpect(jsonPath("$.data.qitemCpstCn").doesNotExist())
                .andExpect(jsonPath("$.data.sysFormCd").doesNotExist());
    }

    // 마감된 지정 폼은 200이고 receiptStatus가 마감을 말한다 — www가 «준비 중»과 «마감»을 가른다
    @Test
    void closedRecruitFormMetaReportsClosed() throws Exception {
        Long formId = saveForm("지난 모집", "끝", FormStatus.OPEN, null);
        close(formId);
        designate(formId, "RECRUIT");

        mockMvc.perform(get(SYSTEM_META, "RECRUIT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receiptStatus").value("CLOSED"));
    }

    /*
     * 404 하나로 묶는다 — 아직 지정된 폼이 없음 · 지정된 폼이 아직 DRAFT · RECRUIT 밖의 코드(기획안
     * PROPOSAL 포함). 코드를 나누면 어느 코드가 있는지, 지정 전인지가 익명에게 드러난다.
     */
    @Test
    void undesignatedDraftAndOtherCodesAreAllNotFound() throws Exception {
        mockMvc.perform(get(SYSTEM_META, "RECRUIT"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        Long draft = saveForm("준비 중인 모집", "아직", FormStatus.DRAFT, null);
        designate(draft, "RECRUIT");
        mockMvc.perform(get(SYSTEM_META, "RECRUIT"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        Long proposal = saveForm("기획안", "부원 전용", FormStatus.OPEN, null);
        designate(proposal, "PROPOSAL");
        mockMvc.perform(get(SYSTEM_META, "PROPOSAL"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(get(SYSTEM_META, "NO_SUCH_CODE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /*
     * RECRUIT 폼은 /forms/open에 여전히 뜨지 않는다 — 시스템 폼을 빼는 ADR-0038의 규칙은 그대로이고,
     * 신입회원 모집은 /join이 위 메타로 그리는 것이지 «지금 지원할 수 있는 것» 카드가 아니다.
     */
    @Test
    void openFormsListStillExcludesTheRecruitForm() throws Exception {
        Long recruit = saveForm("신입회원 모집", "모집", FormStatus.OPEN, null);
        designate(recruit, "RECRUIT");
        saveForm("평범한 설문", "설문", FormStatus.OPEN, null);

        mockMvc.perform(get("/public/v1/forms/open"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].formTtlNm").value("평범한 설문"));
    }

    /* ── 표본 ───────────────────────────────────────────────── */

    private Long saveForm(
            String title, String pageDescription, FormStatus status, Instant receiptEndAt) {
        return saveForm(title, pageDescription, status, null, receiptEndAt);
    }

    private Long saveFormWithPeriod(
            String title, FormStatus status, Instant receiptBeginAt, Instant receiptEndAt) {
        return saveForm(title, null, status, receiptBeginAt, receiptEndAt);
    }

    private Long saveForm(
            String title,
            String pageDescription,
            FormStatus status,
            Instant receiptBeginAt,
            Instant receiptEndAt) {
        QuestionCompositionContent composition =
                new QuestionCompositionContent(
                        List.of(new Page("기본 정보", pageDescription)),
                        List.of(
                                new QuestionItem(
                                        "q1",
                                        "이름",
                                        QuestionItemType.SHORT_TEXT,
                                        true,
                                        0,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null)));
        return formRepository
                .saveAndFlush(
                        FormEntity.create(
                                creator, title, composition, receiptBeginAt, receiptEndAt, status))
                .getId();
    }

    /*
     * 시스템 폼으로 세운다 (#520). API(PUT /v1/forms/system/{code})가 아니라 엔티티로 하는 것은
     * 이 테스트가 익명 경로만 보기 때문이다 — 지정 API의 배선은 FormControllerTest가 본다.
     */
    private void designate(Long formId, String systemFormCode) {
        FormEntity form = formRepository.findById(formId).orElseThrow();
        form.designateAsSystemForm(systemFormCode);
        formRepository.saveAndFlush(form);
    }

    private UUID keyOf(Long formId) {
        return formRepository.findById(formId).orElseThrow().getFormKey();
    }

    /* 마감은 전이로만 만든다 — DRAFT에서 CLOSED로 가는 길이 없다는 규칙에 예외를 두지 않는다 */
    private void close(Long formId) {
        formRepository.findById(formId).orElseThrow().changeStatus(FormStatusAction.CLOSE);
        formRepository.flush();
    }
}
