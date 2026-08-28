package org.sscc.ssccopsserver.support;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramTypeEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.CurriculumItemEntity;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramTypeRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.CurriculumItemRepository;
import org.sscc.ssccopsserver.domain.event.entity.EventClassificationEntity;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.event.repository.EventClassificationRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
import org.sscc.ssccopsserver.domain.form.entity.ResponseContent;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseHistoryRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/*
 * 학술 활동(#131) 테스트 픽스처. 등록 API가 없어졌다(폼 응답 승인 이관 ssccops#148로 대체,
 * 2026-08-23 설계 변경) — 조회 테스트가 필요로 하는 AcademicProgram+Event+CurriculumItem 행을
 * 이 픽스처가 리포지토리로 직접 만든다. 실제 생성 로직(폼 승인 시점의 오케스트레이션)은
 * #148의 몫이며, 이 픽스처는 그 결과 모양을 흉내만 낸다.
 */
public final class AcademicProgramFixture {

    private AcademicProgramFixture() {}

    private static final Instant DEFAULT_EVENT_BGNG_DT = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant DEFAULT_EVENT_END_DT = Instant.parse("2026-12-01T00:00:00Z");

    public static AcademicProgramEntity save(
            EventRepository eventRepository,
            EventClassificationRepository eventClassificationRepository,
            AcademicProgramRepository academicProgramRepository,
            AcademicProgramTypeRepository academicProgramTypeRepository,
            CurriculumItemRepository curriculumItemRepository,
            FormRepository formRepository,
            FormResponseHistoryRepository formResponseHistoryRepository,
            String typeCd,
            String title,
            MemberEntity proposer,
            List<String> curriculumTitles) {
        return save(
                eventRepository,
                eventClassificationRepository,
                academicProgramRepository,
                academicProgramTypeRepository,
                curriculumItemRepository,
                formRepository,
                formResponseHistoryRepository,
                typeCd,
                title,
                proposer,
                curriculumTitles,
                DEFAULT_EVENT_BGNG_DT,
                DEFAULT_EVENT_END_DT);
    }

    /*
     * 정렬·페이징 테스트처럼 등록 순서가 아니라 event_bgng_dt로 결과 순서를 통제해야 하는
     * 경우를 위한 오버로드다 — createdAt은 감사 컬럼이라 값을 직접 지정할 수 없고, 두 행을
     * 빠르게 연달아 만들면 시각 분해능에 따라 같은 값으로 찍혀 정렬이 흔들릴 수 있다.
     */
    public static AcademicProgramEntity save(
            EventRepository eventRepository,
            EventClassificationRepository eventClassificationRepository,
            AcademicProgramRepository academicProgramRepository,
            AcademicProgramTypeRepository academicProgramTypeRepository,
            CurriculumItemRepository curriculumItemRepository,
            FormRepository formRepository,
            FormResponseHistoryRepository formResponseHistoryRepository,
            String typeCd,
            String title,
            MemberEntity proposer,
            List<String> curriculumTitles,
            Instant eventBgngDt,
            Instant eventEndDt) {
        // 행사 분류는 학술 활동 전용 코드가 없다 — wave2가 시드하는 일반 분류를 그대로 쓴다
        // (event_clsf 결정은 #148의 몫, 2026-08-23 설계 변경).
        EventClassificationEntity classification =
                eventClassificationRepository.findById("EVENT").orElseThrow();
        AcademicProgramTypeEntity type =
                academicProgramTypeRepository.findById(typeCd).orElseThrow();

        EventEntity event =
                eventRepository.save(
                        EventEntity.create(
                                classification,
                                proposer,
                                title,
                                "본문",
                                null,
                                null,
                                eventBgngDt,
                                eventEndDt,
                                null,
                                null));

        AcademicProgramEntity academicProgram =
                academicProgramRepository.save(
                        AcademicProgramEntity.create(
                                event,
                                saveProposalResponse(
                                        formRepository,
                                        formResponseHistoryRepository,
                                        proposer,
                                        title),
                                type,
                                "목표",
                                null,
                                null,
                                null,
                                null,
                                proposer));

        int seqno = 1;
        for (String curriculumTitle : curriculumTitles) {
            curriculumItemRepository.save(
                    CurriculumItemEntity.create(
                            academicProgram, seqno++, curriculumTitle, LocalDate.now()));
        }

        return academicProgram;
    }

    /*
     * 이 활동이 태어난 기획안 응답 (#150 · acdm_actv.form_rspns_id는 NOT NULL이다).
     *
     * 실제 폼(sys_form_cd = 'PROPOSAL')의 응답을 흉내 내지 않고 문항 없는 폼에 빈 응답을
     * 하나 만드는 것은, 이 픽스처를 쓰는 테스트들이 검증하는 것이 이관이 아니라 **이미
     * 만들어진 활동의 조회·전이·출석**이기 때문이다 — 그쪽에 진짜 기획안 내용을 채우면
     * 이관 규칙이 픽스처에 복제되어, #150의 파서가 바뀔 때 조회 테스트가 함께 빨개진다.
     * 이관 자체를 검증하는 것은 AcademicProgramMigration* 테스트이며 그쪽은 폼 시드를 그대로 쓴다.
     *
     * 활동마다 폼을 새로 만드는 것은 (form_id, mbr_id, rspns_seq) UNIQUE 때문이다 — 한 회원이
     * 여러 활동의 제출자인 테스트가 있어 폼을 공유하면 두 번째 응답이 제약에 걸린다.
     */
    private static FormResponseHistoryEntity saveProposalResponse(
            FormRepository formRepository,
            FormResponseHistoryRepository formResponseHistoryRepository,
            MemberEntity proposer,
            String title) {
        FormEntity form =
                formRepository.save(
                        FormEntity.create(
                                proposer,
                                title + " 기획안",
                                new QuestionCompositionContent(null, List.of()),
                                null,
                                null));
        return formResponseHistoryRepository.save(
                FormResponseHistoryEntity.createSubmitted(
                        form, proposer, ResponseContent.of(null), Instant.now()));
    }
}
