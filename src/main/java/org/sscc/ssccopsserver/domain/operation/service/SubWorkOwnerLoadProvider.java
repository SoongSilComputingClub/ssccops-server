package org.sscc.ssccopsserver.domain.operation.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.service.MemberSubWorkLoadProvider;
import org.sscc.ssccopsserver.domain.operation.entity.WorkStatus;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkRepository;

import lombok.RequiredArgsConstructor;

/*
 * 한 회원이 담당 중인 하위 업무가 몇 건인가 (ssccops#242).
 *
 * 회원 도메인이 선언한 포트(MemberSubWorkLoadProvider)의 구현이며, 운영 도메인이 답한다 —
 * SubWorkSharePreviewProvider가 공유 도메인의 포트를 구현하는 것과 같은 모양이다.
 *
 * ── 왜 SubWorkServiceImpl에 얹지 않는가 ────────────────────────
 * 한 클래스가 두 도메인의 인터페이스를 함께 구현하면 **@MockitoBean이 그 빈을 통째로 갈아
 * 끼운다.** 실제로 MemberChangeControllerTest가 이 포트를 목으로 바꾸자 같은 빈을
 * SubWorkService로 주입받던 DashboardServiceImpl이 BeanNotOfRequiredTypeException으로 죽어
 * 컨텍스트가 아예 뜨지 않았다(16개 실패). 포트 구현을 따로 두면 목으로 바꿔도 갈리는 것이
 * 그 포트 하나뿐이다.
 *
 * 질의를 리포지토리에서 직접 부르는 것도 그래서다 — SubWorkService를 거치면 그 빈을 다시
 * 물게 되어 나눈 이유가 없어진다. 세는 규칙 자체(완료·삭제 제외)는 종전대로
 * SubWorkRepository.countByOwnerIdExcludingStatus 한 곳에 있다.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SubWorkOwnerLoadProvider implements MemberSubWorkLoadProvider {

    private final SubWorkRepository subWorkRepository;

    /*
     * 식별자가 없으면 0이다 — 부르는 쪽(회원 도메인)이 회원을 손에 쥔 채 호출하므로 실제로는
     * 일어나지 않지만, null을 그대로 흘려보내면 조건이 조용히 아무것도 세지 않는 쪽으로 무너진다.
     */
    @Override
    public long countOngoingByOwner(Long ownerId) {
        if (ownerId == null) {
            return 0L;
        }
        return subWorkRepository.countByOwnerIdExcludingStatus(ownerId, WorkStatus.DONE);
    }
}
