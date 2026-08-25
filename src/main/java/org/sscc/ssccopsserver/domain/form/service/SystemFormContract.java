package org.sscc.ssccopsserver.domain.form.service;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/*
 * 코드와 데이터의 계약 (#140) — sys_form_cd별로 "코드가 그 폼에서 반드시 읽는 qitemId"를 선언한다.
 *
 * 이 표가 계약 그 자체다. 어딘가의 서비스가 form.getQuestionComposition()에서 "applicantName"을
 * 꺼내 쓰기 시작하면 그 사실이 코드 안에만 있고, 운영자가 편집 화면에서 그 문항을 지우는 순간
 * 조용히 빈 값이 읽힌다 — 터지지 않고 틀리는 종류다. 그래서 요구하는 쪽이 여기에 적고,
 * 폼 저장 경로가 그 선언을 근거로 거절한다(FormEntity.requireSystemContractKept).
 *
 * **첫 항목은 PROPOSAL이다** (#173). 기획안을 폼으로 받고 승인 시점에 학술 활동으로 이관한다는
 * 결정이 끝나(ssccops#131) 시드가 세워졌고, 그 폼의 qitemId가 여기 실린다.
 *
 * 상수 하나면 될 것을 빈으로 두는 것은 테스트가 계약을 갈아 끼울 수 있어야 해서다. 표를 비운 채로
 * 잠금 경로 전체(컨트롤러 → 서비스 → 엔티티)를 검증할 방법이 없는데, static으로 두면 그 검증이
 * 엔티티 단위 테스트까지만 닿고 배선이 끊겨도 초록으로 남는다.
 */
@Component
public class SystemFormContract {

    /*
     * 코드가 선언한 계약 (#173 · PROPOSAL).
     *
     * 값을 문자열 리터럴로 다시 적지 않고 ProposalFormSeed의 상수를 가리킨다 — 시드가 넣는
     * qitemId와 계약이 요구하는 qitemId는 같은 문자열이어야 하는데, 두 곳에 따로 적으면 오타
     * 하나가 "시드한 폼이 자기 계약을 어긴 상태"로 배포된다(저장을 시도해야 400으로 드러난다).
     *
     * **잠그는 것은 없으면 이관이 성립하지 않는 문항뿐이다.** 선택 문항(준비물·정기 일정·정원·
     * 희망 장소)은 #150이 읽기는 하지만 비어 있어도 되는 값이라, 문항을 지운 것과 제출자가
     * 비워 둔 것의 결과가 같다 — 잠글 이득 없이 운영진이 회차마다 폼을 다듬을 여지만 없앤다.
     * 고르는 근거는 그 자리에 있고(ProposalFormSeed.MIGRATION_REQUIRED_QITEM_IDS 주석) 여기서
     * 다시 판단하지 않는다.
     */
    private static final Map<String, Set<String>> DECLARED =
            Map.of(
                    ProposalFormSeed.SYSTEM_FORM_CODE,
                    ProposalFormSeed.MIGRATION_REQUIRED_QITEM_IDS);

    private final Map<String, Set<String>> requiredQitemIds;

    /** 스프링이 쓰는 생성자. 인자 있는 생성자와 둘이지만 주입 대상이 없어 이쪽이 선택된다 */
    public SystemFormContract() {
        this(DECLARED);
    }

    /** 테스트가 계약을 갈아 끼우는 자리 (클래스 주석 참고) */
    public SystemFormContract(Map<String, Set<String>> requiredQitemIds) {
        this.requiredQitemIds = Map.copyOf(requiredQitemIds);
    }

    /*
     * 이 코드가 요구하는 qitemId 집합. 선언이 없는 코드는 빈 집합이며 그 폼은 문항을 자유롭게
     * 고칠 수 있다 — 시스템 폼이라는 표시와 요구 문항의 존재는 별개다. 계약이 없다고 잠금까지
     * 풀리지는 않는다(삭제는 여전히 막힌다).
     */
    public Set<String> requiredQitemIdsOf(String systemFormCode) {
        if (systemFormCode == null) {
            return Set.of();
        }
        return requiredQitemIds.getOrDefault(systemFormCode, Set.of());
    }
}
