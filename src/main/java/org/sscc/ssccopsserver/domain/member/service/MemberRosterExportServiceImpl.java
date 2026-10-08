package org.sscc.ssccopsserver.domain.member.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.code.MemberGradeCode;
import org.sscc.ssccopsserver.domain.member.code.MemberStatusCode;
import org.sscc.ssccopsserver.domain.member.code.RosterPositionNotation;
import org.sscc.ssccopsserver.domain.member.code.error.MemberErrorCode;
import org.sscc.ssccopsserver.domain.member.dto.MemberRosterExportCondition;
import org.sscc.ssccopsserver.domain.member.dto.MemberRosterFile;
import org.sscc.ssccopsserver.domain.member.dto.MemberRosterRow;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleAssignmentEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberStatusEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.global.apipayload.code.error.CommonErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;
import org.sscc.ssccopsserver.global.audit.AuditAction;
import org.sscc.ssccopsserver.global.audit.AuditEvent;
import org.sscc.ssccopsserver.global.audit.AuditLog;

import lombok.RequiredArgsConstructor;

/*
 * 회원명부 내보내기 (#674 · 상위 ssccops#598 · Epic ssccops#599).
 *
 * 동아리연합회에 학기마다 내는 회원명부를 연합회 양식(xlsx) 그대로 만든다. 같은 양식을 옵션 둘로
 * 동아리 내부용으로도 쓴다 — 포함할 회원 상태와 직책 표기법은 서로 독립이다.
 *
 * ── 누가 들어가는가 ────────────────────────────────────────────
 * 오늘(주입된 Clock) 기준으로
 *   ① 회장·부회장 역할이 유효한 회원 — 상태·등급과 무관하게 **항상**. 제출본에서 대표자가 빠지면
 *      안 되고, 임시회원인 회장은 등급이 갱신되지 않았을 뿐 회장이다.
 *   ② 고른 상태(기본: 재학) 중 하나이면서 **임시회원(TEMP)이 아닌** 회원.
 * 임시회원 제외는 가입만 하고 운영진이 아직 확인하지 않은 사람을 명부에 올리지 않기 위한 고정
 * 규칙이라 옵션으로 열지 않는다(운영진 결정 2026-10-09). 상태가 «포함 목록»인 이유는
 * MemberRepository.findRosterMembers 주석에 있다.
 *
 * ── 직책은 무엇으로 적는가 ──────────────────────────────────────
 * 회장·부회장은 언제나 «회장»·«부회장»(한 사람이 둘 다면 회장). 나머지는 RosterPositionNotation —
 * 동아리연합회 표기법이면 «정회원», SSCC 표기법이면 대표 역할 이름(없으면 빈칸). **등급은 직책에
 * 쓰지 않는다** — 등급을 읽는 자리는 위 ②의 임시회원 제외 하나뿐이다.
 *
 * 대표 역할은 담당자 후보 응답의 representativeRoleName과 같은 기준이다(BR-M26 · 유효한 배정 중
 * rprs_role_yn = true, 여럿이면 역할 표시 순번의 첫 번째). 대표가 없으면 다른 역할을 골라 채우지
 * 않는다 — 고르는 규칙이 하나 더 생기면 화면의 대표 역할 표시와 명부가 갈린다.
 *
 * ── 회장이 없으면 409 ──────────────────────────────────────────
 * 옵션과 무관하게 거절한다(운영진 결정 2026-10-09). 회장을 역할 이름으로 찾으므로(권한으로 찾으면
 * 최고관리자가 회장이 된다 — MemberRoleAssignmentRepository.findValidByRoleNames) 이름이 바뀌면
 * 조용히 못 찾는데, 빈 행으로 내면 제출본에서 회장이 빠진 것을 아무도 모른다. 워크북을 만들기
 * **전에** 던져 첫 바이트 전에 끊는다 — 그래야 GlobalExceptionHandler가 상태 코드 + 봉투로 내고 화면이
 * 파일 대신 이유를 띄운다. 부회장은 없어도 거절하지 않는다.
 *
 * ── 트랜잭션 ───────────────────────────────────────────────────
 * 조회 셋과 워크북 쓰기가 한 읽기 트랜잭션이다. 워크북은 회원 수백 명 규모라 짧고(외부 호출이
 * 없다), 감사는 이 트랜잭션이 커밋된 뒤에 쓰인다(AuditLog) — 쓰기가 실패해 파일이 나가지 않으면
 * «내려받았다»도 남지 않는다.
 */
@Service
@RequiredArgsConstructor
public class MemberRosterExportServiceImpl implements MemberRosterExportService {

    /*
     * 역할 이름이 곧 판정 기준이다(V3 시드의 role_nm). 이름을 바꾸면 회장을 못 찾아 409가 나고,
     * 그것이 이름 판정의 대가를 드러내는 자리다 — 조용히 빈 행이 되지 않는다.
     */
    static final String PRESIDENT = "회장";

    static final String VICE_PRESIDENT = "부회장";

    /* 동아리연합회 표기법에서 회장·부회장이 아닌 회원의 직책 */
    static final String FEDERATION_MEMBER_POSITION = "정회원";

    /* 직책 역할 분류(V3 시드의 role_clsf). role_nm이 UNIQUE가 아니라 분류까지 함께 본다 */
    private static final String POSITION_CLASSIFICATION = "POSITION";

    private static final Comparator<MemberEntity> ROSTER_ORDER =
            Comparator.comparing(
                            MemberEntity::getStudentNumber,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(MemberEntity::getName)
                    .thenComparing(MemberEntity::getId);

    private final MemberRepository memberRepository;
    private final MemberStatusRepository memberStatusRepository;
    private final MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    private final MemberRosterWorkbookWriter workbookWriter;
    private final AuditLog auditLog;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public MemberRosterFile export(MemberRosterExportCondition condition) {
        RosterPositionNotation notation = RosterPositionNotation.from(condition.positionNotation());
        List<String> statusCodes = includedStatusCodesOf(condition.mbrSttsCd());
        LocalDate today = LocalDate.now(clock);

        Map<Long, MemberRosterRow> officers = officersOn(today);
        List<MemberEntity> others =
                memberRepository
                        .findRosterMembers(statusCodes, MemberGradeCode.TEMP.code())
                        .stream()
                        .filter(member -> !officers.containsKey(member.getId()))
                        .sorted(ROSTER_ORDER)
                        .toList();
        Map<Long, String> positions = positionsOf(others, notation, today);

        List<MemberRosterRow> rows = new ArrayList<>(officers.values());
        for (MemberEntity member : others) {
            rows.add(rowOf(member, positions.get(member.getId())));
        }

        int year = condition.year();
        int semester = condition.semester();
        byte[] content =
                workbookWriter.write(MemberRosterWorkbookWriter.titleOf(year, semester), rows);

        // 감사: 명부 한 벌이 나간 사실과 옵션(코드값)만 — 회원 값은 싣지 않는다 (ADR-0024)
        auditLog.record(
                AuditEvent.success(AuditAction.MEMBER_ROSTER_EXPORT)
                        .target(year + "-" + semester)
                        .decision(
                                "rows="
                                        + rows.size()
                                        + " positionNotation="
                                        + notation
                                        + " mbrSttsCd="
                                        + String.join(",", statusCodes))
                        .build());
        return new MemberRosterFile(
                MemberRosterWorkbookWriter.fileNameOf(year, semester),
                new ByteArrayResource(content));
    }

    /*
     * 포함할 상태 코드. 비어 있으면 재학 하나다 — 옵션을 건드리지 않은 요청이 곧 연합회 제출용이다.
     *
     * 기준 코드 검사는 enum이 아니라 mbr_stts를 본다. 화면의 체크박스가 GET /v1/member-statuses
     * (테이블)에서 오므로, enum으로 검사하면 테이블에 더한 상태가 화면에는 보이는데 내려받기는 400이
     * 된다(MemberStatusCode.from이 모르는 코드를 null로 다루는 것과 같은 전제다).
     */
    private List<String> includedStatusCodesOf(List<String> requested) {
        Set<String> codes = new LinkedHashSet<>();
        if (requested != null) {
            for (String code : requested) {
                if (code != null && !code.isBlank()) {
                    codes.add(code.trim());
                }
            }
        }
        if (codes.isEmpty()) {
            return List.of(MemberStatusCode.ENROLLED.code());
        }

        Set<String> known =
                memberStatusRepository.findAllById(codes).stream()
                        .map(MemberStatusEntity::getCode)
                        .collect(Collectors.toSet());
        List<String> unknown = codes.stream().filter(code -> !known.contains(code)).toList();
        if (!unknown.isEmpty()) {
            throw new GeneralException(
                    CommonErrorCode.INVALID_CODE_VALUE,
                    "기준 코드에 없는 회원 상태입니다: " + String.join(", ", unknown));
        }
        return List.copyOf(codes);
    }

    /*
     * 회장 → 부회장 순의 행. 같은 역할 안에서는 학번 순이고, 한 사람이 둘 다면 회장 한 줄이다.
     * 회장이 한 명도 없으면 여기서 409로 끊는다(클래스 주석).
     */
    private Map<Long, MemberRosterRow> officersOn(LocalDate today) {
        List<MemberRoleAssignmentEntity> assignments =
                memberRoleAssignmentRepository.findValidByRoleNames(
                        POSITION_CLASSIFICATION, List.of(PRESIDENT, VICE_PRESIDENT), today);

        Map<Long, MemberRosterRow> officers = new LinkedHashMap<>();
        for (String position : List.of(PRESIDENT, VICE_PRESIDENT)) {
            assignments.stream()
                    .filter(assignment -> position.equals(assignment.getRole().getName()))
                    .map(MemberRoleAssignmentEntity::getMember)
                    .sorted(ROSTER_ORDER)
                    .forEach(
                            member ->
                                    officers.putIfAbsent(member.getId(), rowOf(member, position)));
        }
        if (officers.values().stream().noneMatch(row -> PRESIDENT.equals(row.position()))) {
            throw new GeneralException(MemberErrorCode.ROSTER_PRESIDENT_MISSING);
        }
        return officers;
    }

    /*
     * 회장·부회장이 아닌 회원의 직책. 동아리연합회 표기법은 전원 «정회원»이고, SSCC 표기법은 대표
     * 역할 이름이다(없으면 null → 빈칸). 대표 역할은 회원 수와 무관하게 한 번에 모아 온다 — 회원마다
     * 부르면 그대로 N+1이다(findValidByMemberIds 주석).
     */
    private Map<Long, String> positionsOf(
            List<MemberEntity> members, RosterPositionNotation notation, LocalDate today) {
        Map<Long, String> positions = new HashMap<>();
        if (notation == RosterPositionNotation.FEDERATION) {
            members.forEach(member -> positions.put(member.getId(), FEDERATION_MEMBER_POSITION));
            return positions;
        }
        if (members.isEmpty()) {
            return positions;
        }
        List<Long> memberIds = members.stream().map(MemberEntity::getId).toList();
        // 질의가 회원 → 역할 표시 순번 순이라 putIfAbsent가 «여럿이면 첫 번째»가 된다
        for (MemberRoleAssignmentEntity assignment :
                memberRoleAssignmentRepository.findValidByMemberIds(memberIds, today)) {
            if (Boolean.TRUE.equals(assignment.getRepresentative())) {
                positions.putIfAbsent(
                        assignment.getMember().getId(), assignment.getRole().getName());
            }
        }
        return positions;
    }

    private static MemberRosterRow rowOf(MemberEntity member, String position) {
        return new MemberRosterRow(
                position,
                member.getName(),
                member.getDepartmentName(),
                member.getStudentNumber(),
                member.getAcademicYear(),
                member.getPhoneNumber());
    }
}
