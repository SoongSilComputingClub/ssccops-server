package org.sscc.ssccopsserver.domain.form.service;

import java.util.Optional;

/*
 * 이 폼이 학술 활동에 연결됐는지 알려 주는 포트 (ssccops#242).
 *
 * ── 왜 폼 도메인이 인터페이스를 갖는가 ──────────────────────────
 * 폼 상세는 연결된 학술 활동 id를 함께 내리고(#190), 폼 수정은 학술 연결 폼의 접수 기간 편집을
 * 막는다(모집 관리와 폼 편집이 같은 컬럼을 두고 경쟁하기 때문이다). 둘 다 "이 폼이 학술에
 * 연결됐는가"를 물어야 하는데, 그 판별의 주인은 학술 도메인이다 —
 * form → event → acdm_actv 을 거슬러 오르는 조인이고 그 관계를 아는 것은 학술이다.
 *
 * 폼이 AcademicProgramRepository를 직접 주입받는 쪽이 짧지만 그러면
 * **form → academicprogram → form 순환**이 된다(학술은 모집 폼을 만들고 읽으므로 폼을 29번
 * 부른다). SystemFormApprovalHook이 *"폼이 학술 서비스를 직접 주입받으면 시스템 폼이 하나 늘
 * 때마다 도메인 이름이 하나씩 박힌다"*며 피한 결합이, 리포지토리 직접 참조로 되살아나 있었다.
 *
 * 그래서 같은 모양으로 뒤집는다 — **폼이 필요한 사실의 모양을 선언하고 학술이 답한다.**
 *
 * ── 왜 이름에 '학술'이 들어가는가 ──────────────────────────────
 * SharePreviewProvider가 대상 종류를 코드값으로 추상화한 것과 달리 여기는 구체적인 이름을
 * 쓴다. 그쪽은 대상이 늘어나는 자리(하위 업무 다음에 업무·회의가 온다)지만, 이쪽은 **폼이
 * 이미 자기 API 계약에 academicProgramId를 싣고 있어**(FormDetailResponse) 감출 것이 없다.
 * 없는 일반성을 만들면 읽는 사람이 "다른 무엇이 연결될 수 있나"를 찾게 된다.
 */
public interface AcademicFormLinkProvider {

    /*
     * 이 폼에 연결된 학술 활동 id. 연결이 없으면(평범한 폼) 빈 Optional이다.
     *
     * 학술 이관 폼의 event 분류는 그냥 "EVENT"라 분류 코드로는 일반 폼과 구별되지 않는다(#187)
     * — 이 조회가 유일한 판별이다.
     */
    Optional<Long> academicProgramIdOf(Long formId);
}
