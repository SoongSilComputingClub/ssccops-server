package org.sscc.ssccopsserver.domain.form.service;

import java.util.Map;

import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.code.error.MemberErrorCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.service.AuthorityPolicy;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;
import org.sscc.ssccopsserver.global.audit.AuditAction;
import org.sscc.ssccopsserver.global.audit.AuditEvent;
import org.sscc.ssccopsserver.global.audit.AuditLog;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/*
 * 운영자용 응답 조회의 자격 — FormResponseController의 조회 넷(응답 목록 · 상세 · 기획안 첨부 목록 ·
 * 내려받기)의 유일한 판정 (#694 · ssccops#614 · ADR-0068).
 *
 * 규칙: **RESPONSE_REVIEW, 또는 대상 폼이 아래 표의 시스템 폼이고 그 폼에 짝지어진 권한.** 지금 표는
 * 기획안(PROPOSAL) → 학술 조회(ACADEMIC_PROGRAM_READ) 한 줄이다. 국원이 어드민 «기획안 검토»를 읽기만
 * 하게 하려는 것이고, 검토(POST …/reviews)는 여전히 RESPONSE_REVIEW만 한다.
 *
 * ── 왜 @RequireAuthority가 아닌가 ──────────────────────────────────────
 * 그전에는 컨트롤러 클래스 전체에 RESPONSE_REVIEW 하나였다(남의 지원서를 읽는 것 자체가 심사 권한이고,
 * 핸들러가 늘 때 애노테이션을 빠뜨려 개인정보가 열리는 자리를 만들지 않으려고). 그런데 이번 OR의 한쪽은
 * «경로의 폼이 무엇인가»에 달려 있어 애노테이션이 표현하지 못한다 — 애노테이션은 핸들러 단위이고 AND로만
 * 걸린다. 학술 전체 목록(AcademicProgramOwnershipPolicy.requireReader)과 같은 처지라 같은 방식으로
 * 판정하고, 거절의 모양도 애노테이션과 **똑같이** 맞춘다 — 403 AUTHORITY_REQUIRED · authz.deny
 * 감사(decision = RESPONSE_REVIEW) · warn 로그. 화면과 Kibana가 두 길을 다르게 볼 이유가 없다.
 * «빠뜨린 핸들러가 조용히 열린다»는 이제 애노테이션이 아니라 시험이 지킨다
 * (FormResponseControllerAuthorizationTest — 애노테이션 없는 핸들러는 이 정책을 부르는 넷뿐이다).
 *
 * ── 판정 순서 ──────────────────────────────────────────────────────────
 * RESPONSE_REVIEW를 먼저 본다(대다수 요청 — 폼을 읽지 않는다). 그다음 짝지어진 권한을 보고, 그것이
 * 있을 때만 폼 코드를 읽는다. **폼 존재를 먼저 묻지 않는다** — 애노테이션이 404보다 먼저 403을
 * 냈던 것처럼, RESPONSE_REVIEW가 없는 사람에게 없는 폼과 남의 폼은 같은 403이다. 시스템 폼은 코드로
 * 찾는다(form_id는 IDENTITY라 환경마다 다르다 — findBySystemFormCode 주석).
 *
 * ── 기각한 것 ──────────────────────────────────────────────────────────
 * - 국원에게 RESPONSE_REVIEW를 준다: 기획안 하나를 보려다 모든 폼(행사 신청·모집 지원·신입 모집)의
 *   응답이 열린다.
 * - 학술 도메인에 기획안 전용 조회 API를 둔다: 같은 응답이 두 경로로 나가 연락처 마스킹·모양이 갈린다
 *   (ADR-0001 — 기획안은 폼 위에 선다). 폼은 학술을 모르므로(DomainCycleTest) 표에는 코드와 권한만
 *   적는다 — 그 권한이 무엇을 뜻하는지는 AuthorityCode가 말한다.
 * - FormResponseService.getResponses 안에서 판정한다: 학술 신청자 조회가 같은 메서드에 위임하고
 *   (AcademicProgramRecruitmentServiceImpl.getApplications — 자격은 학술이 이미 판정했다), 거기서
 *   RESPONSE_REVIEW를 요구하면 리더의 신청자 조회가 막힌다. 판정은 운영자 경로의 입구에 둔다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FormResponseReadPolicy {

    /*
     * 시스템 폼 → 그 폼의 응답을 RESPONSE_REVIEW 없이 읽을 수 있는 권한. 줄을 더하는 것은 «그 폼의
     * 응답 전부를 그 권한 보유자에게 연다»는 결정이라 ADR 감이다(ADR-0068이 첫 줄).
     */
    private static final Map<String, AuthorityCode> SYSTEM_FORM_READERS =
            Map.of(ProposalFormSeed.SYSTEM_FORM_CODE, AuthorityCode.ACADEMIC_PROGRAM_READ);

    private final AuthorityPolicy authorityPolicy;
    private final FormRepository formRepository;
    private final AuditLog auditLog;

    /*
     * 읽을 수 없으면 403 AUTHORITY_REQUIRED. target은 감사 줄에 남길 핸들러 이름이다
     * (RequireAuthorityAspect가 시그니처를 남기는 것과 같다).
     */
    public void requireReadable(Long formId, MemberEntity requester, String target) {
        if (requester != null && isReadable(formId, requester.getId())) {
            return;
        }

        log.warn(
                "권한 부족으로 거절. memberId={}, 필요={}, formId={}, target={}",
                requester == null ? null : requester.getId(),
                AuthorityCode.RESPONSE_REVIEW.code(),
                formId,
                target);
        auditLog.record(
                AuditEvent.failure(
                                AuditAction.AUTHZ_DENY,
                                MemberErrorCode.AUTHORITY_REQUIRED.getCode())
                        .target(target)
                        .decision(AuthorityCode.RESPONSE_REVIEW.code())
                        .build());
        throw new GeneralException(MemberErrorCode.AUTHORITY_REQUIRED);
    }

    private boolean isReadable(Long formId, Long memberId) {
        if (authorityPolicy.hasAuthority(memberId, AuthorityCode.RESPONSE_REVIEW)) {
            return true;
        }
        return SYSTEM_FORM_READERS.entrySet().stream()
                .anyMatch(
                        reader ->
                                authorityPolicy.hasAuthority(memberId, reader.getValue())
                                        && isSystemForm(formId, reader.getKey()));
    }

    private boolean isSystemForm(Long formId, String systemFormCode) {
        return formRepository
                .findBySystemFormCode(systemFormCode)
                .map(form -> form.getId().equals(formId))
                .orElse(false);
    }
}
