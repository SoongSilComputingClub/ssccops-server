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
 * **지금 이 표는 비어 있다.** PROPOSAL 시스템 폼 시드가 이번 범위에서 빠졌기 때문이며
 * (기획안을 폼으로 만들지 학술관리 도메인으로 만들지가 ssccops#114와 겹쳐 아직 결정되지 않았다),
 * 이 이슈는 시스템 폼이라는 **장치**만 만든다. 비어 있어도 자리를 미리 두는 것은, 첫 시스템 폼을
 * 세우는 이슈가 "요구 문항을 어디에 적는가"를 새로 정하면 그 결정이 그 도메인 서비스 안에
 * 흩어지기 때문이다.
 *
 * 상수 하나면 될 것을 빈으로 두는 것은 테스트가 계약을 갈아 끼울 수 있어야 해서다. 표가 빈 채로
 * 잠금 경로 전체(컨트롤러 → 서비스 → 엔티티)를 검증할 방법이 없는데, static으로 두면 그 검증이
 * 엔티티 단위 테스트까지만 닿고 배선이 끊겨도 초록으로 남는다.
 */
@Component
public class SystemFormContract {

    /*
     * 코드가 선언한 계약. 첫 항목은 첫 시스템 폼을 세우는 이슈가 넣는다.
     *
     * 예: Map.of("PROPOSAL", Set.of("proposalTitle", "proposalBody"))
     */
    private static final Map<String, Set<String>> DECLARED = Map.of();

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
