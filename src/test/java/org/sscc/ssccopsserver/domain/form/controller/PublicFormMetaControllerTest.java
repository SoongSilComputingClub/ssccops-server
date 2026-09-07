package org.sscc.ssccopsserver.domain.form.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    private static final String META = "/public/v1/forms/{formId}/meta";

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
    void closedAndExpiredFormsAreStillReadable() throws Exception {
        Long closed = saveForm("마감된 모집", "지난 모집", FormStatus.OPEN, null);
        close(closed);
        Long expired = saveForm("기간 지난 모집", "기간이 지난 모집", FormStatus.OPEN, PAST);

        mockMvc.perform(get(META, closed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.formTtlNm").value("마감된 모집"));
        mockMvc.perform(get(META, expired))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.formTtlNm").value("기간 지난 모집"));
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

    /* ── 표본 ───────────────────────────────────────────────── */

    private Long saveForm(
            String title, String pageDescription, FormStatus status, Instant receiptEndAt) {
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
                        FormEntity.create(creator, title, composition, null, receiptEndAt, status))
                .getId();
    }

    /* 마감은 전이로만 만든다 — DRAFT에서 CLOSED로 가는 길이 없다는 규칙에 예외를 두지 않는다 */
    private void close(Long formId) {
        formRepository.findById(formId).orElseThrow().changeStatus(FormStatusAction.CLOSE);
        formRepository.flush();
    }
}
