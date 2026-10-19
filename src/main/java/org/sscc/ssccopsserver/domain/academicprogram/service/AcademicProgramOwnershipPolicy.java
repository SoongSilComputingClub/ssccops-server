package org.sscc.ssccopsserver.domain.academicprogram.service;

import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.academicprogram.code.error.AcademicProgramErrorCode;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.event.repository.EventParticipantRepository;
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
 * "이 활동의 스터디장/팀장 본인인가"의 유일한 구현 (#133 — S2(#132, 폐기)가 만들려던 것을 대신
 * 신설한다). SubWorkOwnershipPolicy(#101)와 같은 층이다 — leadrMbrId는 정적 권한 코드가 아니라
 * "이 활동 한정" 관계라 @RequireAuthority가 대신할 수 없다(학술관리_API설계.md §4).
 *
 * prpsrMbrId 비교는 두지 않는다 — 생성 시점부터 leadrMbrId = prpsrMbrId로 고정되므로(#133
 * 재설계) 별도로 비교할 이유가 없다.
 *
 * 이 이슈(#133)에는 이 정책이 걸리는 공개 엔드포인트가 없다(전이 2종은 ACADEMIC_PROGRAM_MANAGE
 * 단일 권한으로만 판정한다, 학술관리_API설계.md §6). S4~S8(회차 기록·출석·인증사진·신청자 조회)
 * 이 이 클래스를 재사용한다.
 *
 * **"소유권 또는 관리권한"도 여기 있다** (#138 · requireLeaderOrManager). 두 자격의 OR은
 * @RequireAuthority로 표현할 수 없다 — 애노테이션은 클래스·메서드 어디에 붙어도 AND로만 걸리고,
 * 관리권한을 컨트롤러에 걸어 두면 스터디장 본인이 자기 활동의 신청자를 보러 올 때 소유권 판정이
 * 돌기도 전에 403으로 끊긴다. 서비스 레이어에서 두 판정을 나란히 두는 것이 유일한 방법이고,
 * 그 자리를 여기 하나로 모아 둔다 — 승인 이력 조회(#139)가 같은 규칙을 쓴다.
 *
 * **"그 활동의 관계자인가"도 여기 있다** (#688 · ssccops#611 · requireRelated). 리더 · 팀원
 * (event_ptcp, 상태 무관) · 학술국장 셋이다. 원래 인증사진 하나를 가리던 판정(#200
 * SessionFileReferenceViewer)인데, 팀원 명단·회차·출석이 «인증만»이라 동아리 전원이 **남의**
 * 프로그램을 읽었다 — 그 조회들이 «팀원도 봐야 한다»를 근거로 열려 있었지만 코드는 팀원이 아니라
 * 전원에게 열었다(#124의 수용 기준도 «활동 관계자»다). 판정이 두 자리에 있으면 사진은 막히는데 같은
 * 회차의 출석부는 열리는 어긋남이 생기므로 여기 한 벌로 올렸다.
 *
 * **조회와 쓰기의 권한이 갈린다** (#694 · ssccops#614 · ADR-0068). 조회는 ACADEMIC_PROGRAM_READ
 * (…OrReader · 관계자 · requireReader), 쓰기와 «버튼을 켤까»는 ACADEMIC_PROGRAM_MANAGE(…OrManager)다.
 * 국원이 학술 화면을 읽기만 하게 하려고 갈랐다 — 그전에는 requireLeaderOrManager 하나가 조회
 * 다섯(승인 이력·명단 이력·신청자·모집 폼·일정)과 쓰기를 함께 판정해서, 거기에 READ를 OR로 얹으면
 * 쓰기까지 열렸다. READ는 MANAGE의 자식이라 학술국장은 두 쪽 모두 통과한다. **새 조회는 …OrReader를,
 * 새 쓰기는 …OrManager를 고른다** — 조회에 …OrManager를 쓰면 국원에게만 403이 되고, 쓰기에
 * …OrReader를 쓰면 국원이 쓴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AcademicProgramOwnershipPolicy {

    private final AuthorityPolicy authorityPolicy;
    private final EventParticipantRepository eventParticipantRepository;
    private final AuditLog auditLog;

    /*
     * 그 활동의 관계자인가 (#688 — #200 결정 2를 옮겨 왔다).
     *
     * 순서는 싼 것부터다: 소유권은 이미 읽어 둔 엔티티의 식별자 비교라 조회가 없고, 명단 조회는
     * 한 번, 권한 조회는 앞의 둘이 모두 아닐 때만 돈다(requireLeaderOrManager와 같은 태도).
     *
     * 명단은 **상태를 가리지 않는다** — 취소(CANCELLED)된 참가자도 그 활동에 있었던 사람이고,
     * 명단이 활동 이력으로 영구 보존되는 것(D16)과 같은 기준이다.
     *
     * 리더 역할(스터디장 등)을 가졌다는 것은 여기서 아무 뜻이 없다 — 다른 프로그램의 리더는 이
     * 프로그램의 관계자가 아니다(리더 역할은 운영진이 아니다 · #687).
     *
     * 권한 줄은 ACADEMIC_PROGRAM_READ다 (#694 · ADR-0068 — #688에서는 MANAGE였다). 관계자에
     * 학술국장이 든 이유가 감독이고 국원의 읽기도 같은 감독이라 같은 자리에 둔다 — 빼면 국원이 회차
     * 이력은 보는데 거기서 회차 상세로 들어가는 순간 403이다. MANAGE 보유자는 펼침으로 READ를 가지므로
     * 학술국장은 그대로다. 이 판정을 지나는 회차 공유 발급도 따라서 READ로 열린다(«볼 수 있는 사람이
     * 공유한다» · 토큰은 미리보기까지 · ADR-0016). 공유 중지는 …OrManager라 열리지 않는다.
     */
    public boolean isRelated(AcademicProgramEntity academicProgram, MemberEntity requester) {
        if (requester == null) {
            return false;
        }
        if (isLeader(academicProgram, requester)) {
            return true;
        }
        if (eventParticipantRepository.existsByEventAndMember(
                academicProgram.getEvent(), requester)) {
            return true;
        }
        return authorityPolicy.hasAuthority(requester.getId(), AuthorityCode.ACADEMIC_PROGRAM_READ);
    }

    /*
     * 관계자가 아니면 403 (#688). **404로 감추지 않는다** — 서버 AGENTS.md «인가는 403» 규칙이며
     * (사용자 결정 2026-10-18), 존재를 감추는 일은 화면(ssccops-web#809 not-found)이 한다.
     */
    public void requireRelated(AcademicProgramEntity academicProgram, MemberEntity requester) {
        if (isRelated(academicProgram, requester)) {
            return;
        }

        log.warn(
                "학술 활동 관계자 아님. academicProgramId={}, requesterId={}",
                academicProgram.getId(),
                requester == null ? null : requester.getId());
        throw new GeneralException(AcademicProgramErrorCode.FORBIDDEN);
    }

    /*
     * 학술 조회 권한(ACADEMIC_PROGRAM_READ) 보유자만 (#688 · 전체 프로그램 목록).
     *
     * @RequireAuthority를 쓰지 않는 것은 같은 엔드포인트가 조건에 따라 갈리기 때문이다 — 목록은
     * `mine`이 있으면 «내 것»이라 누구나, 없으면 «전부»라 학술 조회 권한 보유자만이다. 애노테이션은
     * 핸들러 단위라 이 분기를 표현하지 못한다. 그래서 거절의 모양을 애노테이션과 **똑같이** 맞춘다 —
     * 403 AUTHORITY_REQUIRED · authz.deny 감사(decision = 요구 권한) — 화면과 Kibana가 두 길을
     * 다르게 볼 이유가 없다(RequireAuthorityAspect 참고).
     *
     * #688에서는 MANAGE를 요구해 이름이 requireManager였다. 전체 목록은 쓰기가 아니라 감독의 첫 조회라
     * READ로 내렸다(#694 · ADR-0068 — 학술국장은 펼침으로 그대로 통과한다).
     */
    public void requireReader(MemberEntity requester, String target) {
        if (requester != null
                && authorityPolicy.hasAuthority(
                        requester.getId(), AuthorityCode.ACADEMIC_PROGRAM_READ)) {
            return;
        }

        log.warn(
                "권한 부족으로 거절. memberId={}, 필요={}, target={}",
                requester == null ? null : requester.getId(),
                AuthorityCode.ACADEMIC_PROGRAM_READ.code(),
                target);
        auditLog.record(
                AuditEvent.failure(
                                AuditAction.AUTHZ_DENY,
                                MemberErrorCode.AUTHORITY_REQUIRED.getCode())
                        .target(target)
                        .decision(AuthorityCode.ACADEMIC_PROGRAM_READ.code())
                        .build());
        throw new GeneralException(MemberErrorCode.AUTHORITY_REQUIRED);
    }

    public void requireLeader(AcademicProgramEntity academicProgram, MemberEntity requester) {
        if (isLeader(academicProgram, requester)) {
            return;
        }

        log.warn(
                "학술 활동 리더 본인 아님. academicProgramId={}, leadrMbrId={}, requesterId={}",
                academicProgram.getId(),
                academicProgram.getLeader() == null ? null : academicProgram.getLeader().getId(),
                requester == null ? null : requester.getId());
        throw new GeneralException(AcademicProgramErrorCode.FORBIDDEN);
    }

    /*
     * 이 활동의 스터디장/팀장 본인이거나 학술국장(ACADEMIC_PROGRAM_MANAGE)인가 (#138). **#694부터
     * 쓰기 판정이다** — 조회는 requireLeaderOrReader로 옮겼다(ADR-0068).
     *
     * 소유권을 먼저 본다. 그쪽은 이미 읽어 둔 엔티티의 식별자 비교라 조회가 없고, 권한 조회는
     * 스터디장이 아닐 때만 돈다 — 자기 활동을 여는 대다수 요청에 쿼리를 더하지 않는다
     * (ApprovalAuthorityPolicy가 실패 경로에서만 capabilityListOf를 부르는 것과 같은 태도).
     *
     * 거절은 소유권만 볼 때와 같은 403 FORBIDDEN이다. 둘 중 어느 자격이 모자랐는지는 응답으로
     * 나누지 않는다 — 화면이 고를 안내가 하나이고, 나누면 "당신은 이 활동의 리더가 아니다"라는
     * 사실이 권한 없는 요청자에게 그대로 새어 나간다(로그로는 층이 구분된다).
     */
    public void requireLeaderOrManager(
            AcademicProgramEntity academicProgram, MemberEntity requester) {
        if (isLeaderOrManager(academicProgram, requester)) {
            return;
        }

        log.warn(
                "학술 활동 리더도 학술국장도 아님. academicProgramId={}, leadrMbrId={}, requesterId={}",
                academicProgram.getId(),
                academicProgram.getLeader() == null ? null : academicProgram.getLeader().getId(),
                requester == null ? null : requester.getId());
        throw new GeneralException(AcademicProgramErrorCode.FORBIDDEN);
    }

    /*
     * requireLeaderOrManager의 판정만 (#612). 거절하지 않는 조회 — 팀원 명단의 isEditable처럼 «버튼을
     * 켤까»를 답하는 자리가 쓴다. 판정을 한 벌로 두려고 require가 이것을 부른다.
     */
    public boolean isLeaderOrManager(
            AcademicProgramEntity academicProgram, MemberEntity requester) {
        return isLeaderOr(academicProgram, requester, AuthorityCode.ACADEMIC_PROGRAM_MANAGE);
    }

    /*
     * 이 활동의 스터디장/팀장 본인이거나 학술 조회 권한(ACADEMIC_PROGRAM_READ) 보유자인가 — **조회**
     * 판정이다 (#694 · ADR-0068). 승인 이력 · 명단 이력 · 신청자 · 모집 폼 · 모집 일정 조회가 쓴다.
     *
     * requireLeaderOrManager와 순서·거절 모양(403 FORBIDDEN · 어느 자격이 모자랐는지 나누지 않는다)이
     * 같고 요구 권한만 다르다. 그 다섯 조회가 #138~#483 동안 …OrManager를 썼던 것은 조회와 쓰기의 권한이
     * 같았기 때문이고, READ가 생기며 갈렸다 — 그 자리에서 쓰기 판정에 READ를 OR로 얹으면 같은 메서드를
     * 부르는 쓰기(모집 폼 문항 저장 · 팀원 추가 · 공유 중지)가 국원에게 열린다.
     */
    public void requireLeaderOrReader(
            AcademicProgramEntity academicProgram, MemberEntity requester) {
        if (isLeaderOrReader(academicProgram, requester)) {
            return;
        }

        log.warn(
                "학술 활동 리더도 학술 조회 권한 보유자도 아님. academicProgramId={}, leadrMbrId={},"
                        + " requesterId={}",
                academicProgram.getId(),
                academicProgram.getLeader() == null ? null : academicProgram.getLeader().getId(),
                requester == null ? null : requester.getId());
        throw new GeneralException(AcademicProgramErrorCode.FORBIDDEN);
    }

    /*
     * requireLeaderOrReader의 판정만. 거절하지 않고 «보여 줄까»를 답하는 자리 — 회차 상세의
     * 수정요청 사유(latestOpinion)가 쓴다.
     */
    public boolean isLeaderOrReader(AcademicProgramEntity academicProgram, MemberEntity requester) {
        return isLeaderOr(academicProgram, requester, AuthorityCode.ACADEMIC_PROGRAM_READ);
    }

    // 소유권을 먼저 본다 — 권한 조회는 스터디장이 아닐 때만 돈다(requireLeaderOrManager 주석)
    private boolean isLeaderOr(
            AcademicProgramEntity academicProgram,
            MemberEntity requester,
            AuthorityCode authority) {
        if (isLeader(academicProgram, requester)) {
            return true;
        }
        return requester != null && authorityPolicy.hasAuthority(requester.getId(), authority);
    }

    public boolean isLeader(AcademicProgramEntity academicProgram, MemberEntity requester) {
        if (requester == null) {
            return false;
        }
        MemberEntity leader = academicProgram.getLeader();
        return leader != null && leader.getId().equals(requester.getId());
    }
}
