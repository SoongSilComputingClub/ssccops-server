package org.sscc.ssccopsserver.domain.academicprogram.service;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramRepository;
import org.sscc.ssccopsserver.domain.event.service.AcademicEventLinkProvider;
import org.sscc.ssccopsserver.domain.form.service.AcademicFormLinkProvider;

import lombok.RequiredArgsConstructor;

/*
 * "무엇이 학술 활동에 연결됐는가"에 답하는 자리 (ssccops#242).
 *
 * 폼 도메인과 행사 도메인이 각자 선언한 포트를 여기서 함께 구현한다 — 둘 다 같은 사실
 * (acdm_actv이 무엇을 물고 있는가)을 다른 각도에서 묻고, 그 관계를 아는 것은 이 도메인이다.
 * 구현을 한 클래스에 둔 것은 그 사실이 하나이기 때문이며, 묻는 쪽이 늘면 여기에 인터페이스가
 * 하나씩 붙는다 — 그 목록이 곧 "학술에 기대는 도메인"의 목록이라 눈에 보이는 편이 낫다.
 *
 * ── 왜 이 방향인가 ────────────────────────────────────────────
 * 반대로 폼·행사가 AcademicProgramRepository를 직접 주입받으면 패키지 순환이 된다
 * (form → academicprogram → form · event → academicprogram → event). 학술은 모집 폼을 만들고
 * 행사를 이관해 만드는 쪽이라 폼·행사를 향한 화살표가 이미 굵고(각 29회·31회), 그 반대 방향은
 * 조회 하나뿐이었다 — 가는 쪽이 아니라 오는 쪽을 뒤집는 것이 맞다.
 *
 * 리포지토리 질의 자체는 그대로 둔다. 이 클래스는 **방향만 바꾸는 얇은 층**이고 판별 규칙은
 * 여전히 AcademicProgramRepository의 두 질의에 있다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AcademicLinkageProvider
        implements AcademicFormLinkProvider, AcademicEventLinkProvider {

    private final AcademicProgramRepository academicProgramRepository;

    /*
     * form → event → acdm_actv을 거슬러 오른다. 학술 이관 폼의 event 분류는 그냥 "EVENT"라
     * 분류 코드로는 일반 폼과 구별되지 않으므로(#187) 이 조인이 유일한 판별이다.
     */
    @Override
    public Optional<Long> academicProgramIdOf(Long formId) {
        return academicProgramRepository.findIdByFormId(formId);
    }

    /*
     * event ↔ acdm_actv은 1:1(uk_acdm_actv_event)이라 event_id 존재만 보면 된다. 빈 입력에
     * 질의를 보내지 않는 것은 호출부(공개 목록)가 행사가 없는 페이지에서도 부르기 때문이다.
     */
    @Override
    public Set<Long> academicEventIdsAmong(Collection<Long> eventIds) {
        if (eventIds.isEmpty()) {
            return Set.of();
        }
        return academicProgramRepository.findEventIdsByEventIdIn(eventIds);
    }
}
