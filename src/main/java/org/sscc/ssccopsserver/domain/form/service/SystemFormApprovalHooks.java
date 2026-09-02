package org.sscc.ssccopsserver.domain.form.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

/*
 * 등록된 SystemFormApprovalHook을 sys_form_cd로 찾아 주는 자리 (#150).
 *
 * 호출부가 List<SystemFormApprovalHook>을 직접 주입받아 매번 stream().filter(...)로 고르는
 * 쪽도 가능했지만, 그러면 "코드가 겹치는 훅이 둘 있으면 어떻게 되는가"의 답이 호출부마다 갈린다
 * (먼저 나온 것을 쓰거나, 조용히 하나만 실행되거나). 여기서 맵으로 굳히면서 겹침을 기동 시점에
 * 터뜨린다 — 두 도메인이 같은 시스템 폼의 승인에 반응하는 것은 설계가 어긋난 상태이지 런타임에
 * 골라야 할 선택지가 아니다.
 *
 * 등록된 훅이 없는 코드(평범한 폼은 sys_form_cd 자체가 NULL이다)는 빈 Optional이며 그것이
 * 정상이다 — 시스템 폼이라는 표시와 승인 후속 처리의 존재는 별개다(SystemFormContract가
 * 계약 없는 시스템 폼을 허용하는 것과 같은 태도).
 */
@Component
public class SystemFormApprovalHooks {

    private final Map<String, SystemFormApprovalHook> hooksBySystemFormCode;

    public SystemFormApprovalHooks(List<SystemFormApprovalHook> hooks) {
        Map<String, SystemFormApprovalHook> registry = new HashMap<>();
        for (SystemFormApprovalHook hook : hooks) {
            SystemFormApprovalHook previous = registry.put(hook.sysFormCd(), hook);
            if (previous != null) {
                throw new IllegalStateException(
                        "같은 sys_form_cd에 승인 훅이 둘 등록됐습니다: " + hook.sysFormCd());
            }
        }
        this.hooksBySystemFormCode = Map.copyOf(registry);
    }

    /** 평범한 폼(sys_form_cd = null)과 훅이 없는 시스템 폼은 모두 빈 Optional이다 */
    public Optional<SystemFormApprovalHook> find(String systemFormCode) {
        if (systemFormCode == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(hooksBySystemFormCode.get(systemFormCode));
    }
}
