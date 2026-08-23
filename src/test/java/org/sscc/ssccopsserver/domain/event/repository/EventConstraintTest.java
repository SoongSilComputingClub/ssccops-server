package org.sscc.ssccopsserver.domain.event.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;
import org.sscc.ssccopsserver.domain.event.code.EventStatus;
import org.sscc.ssccopsserver.domain.event.entity.EventClassificationEntity;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.event.entity.EventParticipantEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.global.config.JpaAuditingConfig;
import org.sscc.ssccopsserver.global.config.JsonFormatMapperConfig;
import org.sscc.ssccopsserver.support.MemberFixture;

/*
 * 행사 테이블 셋업(ssccops#134)의 매핑·제약 검증.
 *
 * UNIQUE 두 개(uk_event_form · uk_event_ptcp_event_member)는 "선조회로는 못 막는 동시 요청"을
 * 막으려고 둔 것이라, 애플리케이션 코드가 아니라 DB가 거절하는지를 봐야 의미가 있다 —
 * FormUniqueConstraintTest와 같은 태도다.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({JpaAuditingConfig.class, JsonFormatMapperConfig.class})
class EventConstraintTest {

    @Autowired private EventRepository eventRepository;
    @Autowired private EventClassificationRepository eventClassificationRepository;
    @Autowired private EventParticipantRepository eventParticipantRepository;
    @Autowired private FormRepository formRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;

    private MemberEntity creator;
    private EventClassificationEntity classification;

    @BeforeEach
    void setUp() {
        creator =
                MemberFixture.save(
                        memberRepository,
                        memberGradeRepository,
                        memberStatusRepository,
                        UUID.randomUUID(),
                        "20260101",
                        "홍길동",
                        "20260101@soongsil.ac.kr");
        classification = eventClassificationRepository.findById("SEMINAR").orElseThrow();
    }

    // 폼 없는 공지(form_id NULL)가 정상 경로다 — 생성 직후 상태는 DRAFT, 감사 컬럼은 자동이다
    @Test
    void persistsAnnouncementEventWithoutForm() {
        EventEntity event =
                eventRepository.saveAndFlush(
                        EventEntity.create(
                                classification,
                                creator,
                                "2026 홈커밍 안내",
                                "# 홈커밍\n\n안내 본문",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null));

        assertThat(event.getId()).isNotNull();
        assertThat(event.getStatus()).isEqualTo(EventStatus.DRAFT);
        assertThat(event.getForm()).isNull();
        assertThat(event.getCreatedAt()).isNotNull();
        assertThat(event.getUpdatedAt()).isNotNull();
    }

    // 한 폼은 최대 한 행사에만 전속된다(D11) — uk_event_form이 DDL까지 내려가는지 확인한다
    @Test
    void rejectsTheSameFormLinkedToTwoEvents() {
        FormEntity form =
                formRepository.saveAndFlush(
                        FormEntity.create(
                                creator,
                                "세미나 신청 폼",
                                new QuestionCompositionContent(List.of(), List.of()),
                                null,
                                null));
        eventRepository.saveAndFlush(eventWithForm("봄 세미나", form));

        assertThatThrownBy(() -> eventRepository.saveAndFlush(eventWithForm("가을 세미나", form)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // 같은 회원을 같은 행사에 두 번 올릴 수 없다(D4) — uk_event_ptcp_event_member 검증
    @Test
    void rejectsTheSameMemberRegisteredTwiceForTheSameEvent() {
        EventEntity event = eventRepository.saveAndFlush(eventWithForm("봄 세미나", null));

        // 수동 등록(전화 접수)이라 신청 근거 폼 응답이 없다 — form_rspns_id NULL이 정상이다
        eventParticipantRepository.saveAndFlush(
                EventParticipantEntity.register(
                        event, creator, EventParticipantStatus.CONFIRMED, null, creator));

        assertThatThrownBy(
                        () ->
                                eventParticipantRepository.saveAndFlush(
                                        EventParticipantEntity.register(
                                                event,
                                                creator,
                                                EventParticipantStatus.WAITLISTED,
                                                null,
                                                creator)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private EventEntity eventWithForm(String title, FormEntity form) {
        return EventEntity.create(
                classification, creator, title, "본문", null, form, null, null, null, null);
    }
}
