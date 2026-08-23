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
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/*
 * 학술 활동(#131) 테스트 픽스처. 등록 API가 없어졌다(폼 응답 승인 이관 ssccops#148로 대체,
 * 2026-08-23 설계 변경) — 조회 테스트가 필요로 하는 AcademicProgram+Event+CurriculumItem 행을
 * 이 픽스처가 리포지토리로 직접 만든다. 실제 생성 로직(폼 승인 시점의 오케스트레이션)은
 * #148의 몫이며, 이 픽스처는 그 결과 모양을 흉내만 낸다.
 */
public final class AcademicProgramFixture {

    private AcademicProgramFixture() {}

    public static AcademicProgramEntity save(
            EventRepository eventRepository,
            EventClassificationRepository eventClassificationRepository,
            AcademicProgramRepository academicProgramRepository,
            AcademicProgramTypeRepository academicProgramTypeRepository,
            CurriculumItemRepository curriculumItemRepository,
            String typeCd,
            String title,
            MemberEntity proposer,
            List<String> curriculumTitles) {
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
                                Instant.parse("2026-09-01T00:00:00Z"),
                                Instant.parse("2026-12-01T00:00:00Z"),
                                null,
                                null));

        AcademicProgramEntity academicProgram =
                academicProgramRepository.save(
                        AcademicProgramEntity.create(
                                event, type, "목표", null, null, null, null, proposer));

        int seqno = 1;
        for (String curriculumTitle : curriculumTitles) {
            curriculumItemRepository.save(
                    CurriculumItemEntity.create(
                            academicProgram, seqno++, curriculumTitle, LocalDate.now()));
        }

        return academicProgram;
    }
}
