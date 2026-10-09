package org.sscc.ssccopsserver.domain.member.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
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
import org.sscc.ssccopsserver.domain.member.code.error.MemberErrorCode;
import org.sscc.ssccopsserver.domain.member.dto.MemberRosterExportCondition;
import org.sscc.ssccopsserver.domain.member.dto.MemberRosterFile;
import org.sscc.ssccopsserver.domain.member.dto.MemberRosterPreviewResponse;
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
 * 동아리연합회에 학기마다 내는 회원명부를 연합회 양식(xlsx) 그대로 만든다. 옵션은 포함할 회원 상태
 * 하나다 — 상태를 넓혀 동아리 내부용으로도 받는다.
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
 * 동아리연합회 표기법 하나다 — 회장·부회장은 «회장»·«부회장»(한 사람이 둘 다면 회장), 나머지는
 * 역할·등급과 무관하게 전원 «정회원». **등급은 직책에 쓰지 않는다** — 등급 FULL(정회원)과 이름만
 * 같고, 등급을 읽는 자리는 위 ②의 임시회원 제외 하나뿐이다.
 *
 * 처음(#674)에는 SSCC 표기법(대표 역할 이름 · 없으면 빈칸)도 골랐으나, 연합회 제출용만 필요하다는
 * 회장 확인(2026-10-09)으로 옵션을 걷어냈다(#678 · ssccops#600). 옛 화면이 positionNotation을 실어
 * 보내도 조건 레코드에 그 필드가 없어 바인딩이 버린다 — 거절하지 않는다.
 *
 * ── 제목의 괄호는 고른 상태를 말한다 ────────────────────────────
 * 양식 원문은 «회원명부(재학생)»이고, 재학만 고르면(= 연합회 제출용 기본값) 그대로 둔다. 상태를
 * 넓히면 고른 상태 이름을 표시 순번대로 «·»로 이어 적고(«(재학·일반휴학)»), 전부 고르면 괄호를 뺀다
 * (statusLabelOf). 처음(#674)에는 «(재학생)»을 옵션과 무관하게 두기로 했으나(ssccops#598의
 * 2026-10-09 결정) 휴학·졸업을 넣은 내부용 명부가 «재학생 명부»라고 적혀 나가 같은 날 뒤집었다(#676).
 *
 * ── 회장이 없으면 409 ──────────────────────────────────────────
 * 옵션과 무관하게 거절한다(운영진 결정 2026-10-09). 회장을 역할 이름으로 찾으므로(권한으로 찾으면
 * 최고관리자가 회장이 된다 — MemberRoleAssignmentRepository.findValidByRoleNames) 이름이 바뀌면
 * 조용히 못 찾는데, 빈 행으로 내면 제출본에서 회장이 빠진 것을 아무도 모른다. 워크북을 만들기
 * **전에** 던져 첫 바이트 전에 끊는다 — 그래야 GlobalExceptionHandler가 상태 코드 + 봉투로 내고 화면이
 * 파일 대신 이유를 띄운다. 부회장은 없어도 거절하지 않는다. **미리보기는 거절하지 않고**
 * presidentMissing으로 알린다(MemberRosterPreviewResponse).
 *
 * ── 미리보기 ───────────────────────────────────────────────────
 * 내려받기와 같은 판정(select)을 거쳐 줄 수와 «왜 빠졌나»별 인원, 제목·파일 이름을 낸다. 회원 값이
 * 실리지 않으므로 감사를 남기지 않는다 — 목록 조회를 남기지 않는 것과 같은 판단이다.
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

    /* 회장·부회장이 아닌 회원의 직책 — 동아리연합회 표기법 */
    static final String FEDERATION_MEMBER_POSITION = "정회원";

    /*
     * 재학만 골랐을 때 제목 괄호에 적는 말 — 상태 이름(«재학»)이 아니라 연합회 양식의 원문이다.
     * 상태 이름은 코드에 적지 않고 mbr_stts에서 읽는다(MemberImportReferenceData와 같은 규칙).
     */
    static final String ENROLLED_ONLY_LABEL = "재학생";

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
        Selection selection = select(condition);
        if (!selection.hasPresident()) {
            throw new GeneralException(MemberErrorCode.ROSTER_PRESIDENT_MISSING);
        }
        List<MemberRosterRow> rows = new ArrayList<>(selection.officers().values());
        for (MemberEntity member : selection.others()) {
            rows.add(rowOf(member, FEDERATION_MEMBER_POSITION));
        }

        int year = condition.year();
        int semester = condition.semester();
        byte[] content = workbookWriter.write(selection.title(), rows);

        // 감사: 명부 한 벌이 나간 사실과 옵션(코드값)만 — 회원 값은 싣지 않는다 (ADR-0024)
        auditLog.record(
                AuditEvent.success(AuditAction.MEMBER_ROSTER_EXPORT)
                        .target(year + "-" + semester)
                        .decision(
                                "rows="
                                        + rows.size()
                                        + " mbrSttsCd="
                                        + String.join(",", selection.statusCodes()))
                        .build());
        return new MemberRosterFile(
                MemberRosterWorkbookWriter.fileNameOf(year, semester),
                new ByteArrayResource(content));
    }

    /*
     * 미리보기. 빠진 회원을 «임시회원 → 고르지 않은 상태» 순으로 가른다 — 임시회원은 어느 옵션에서도
     * 들어오지 않으므로 상태와 무관하게 먼저 세고, 나머지 빠진 사람이 상태 탓이다. 세 수의 합이 전체
     * 회원 수가 되게 해 «DB는 16명인데 명부는 8줄»이 숫자만으로 설명되게 한다.
     */
    @Override
    @Transactional(readOnly = true)
    public MemberRosterPreviewResponse preview(MemberRosterExportCondition condition) {
        Selection selection = select(condition);
        long officerCount = selection.officers().size();
        long rowCount = officerCount + selection.others().size();
        long excludedTemporaryCount =
                memberRepository.findIdsByGradeCode(MemberGradeCode.TEMP.code()).stream()
                        .filter(id -> !selection.officers().containsKey(id))
                        .count();
        long totalMemberCount = memberRepository.count();
        return new MemberRosterPreviewResponse(
                selection.today(),
                selection.title(),
                MemberRosterWorkbookWriter.fileNameOf(condition.year(), condition.semester()),
                rowCount,
                officerCount,
                excludedTemporaryCount,
                totalMemberCount - rowCount - excludedTemporaryCount,
                totalMemberCount,
                !selection.hasPresident());
    }

    /*
     * 내려받기와 미리보기가 같이 쓰는 판정 — 누가 들어가는가와 제목까지. 회장이 없을 때 거절할지는
     * 부르는 쪽이 정한다.
     */
    private Selection select(MemberRosterExportCondition condition) {
        IncludedStatuses statuses = includedStatusesOf(condition.mbrSttsCd());
        LocalDate today = LocalDate.now(clock);

        Map<Long, MemberRosterRow> officers = officersOn(today);
        List<MemberEntity> others =
                memberRepository
                        .findRosterMembers(statuses.codes(), MemberGradeCode.TEMP.code())
                        .stream()
                        .filter(member -> !officers.containsKey(member.getId()))
                        .sorted(ROSTER_ORDER)
                        .toList();
        String title =
                MemberRosterWorkbookWriter.titleOf(
                        condition.year(), condition.semester(), statuses.label());
        return new Selection(statuses.codes(), today, officers, others, title);
    }

    /*
     * 포함할 상태 코드(표시 순번 순)와 제목 괄호. 비어 있으면 재학 하나다 — 옵션을 건드리지 않은
     * 요청이 곧 연합회 제출용이다.
     *
     * 기준 코드 검사는 enum이 아니라 mbr_stts를 본다. 화면의 체크박스가 GET /v1/member-statuses
     * (테이블)에서 오므로, enum으로 검사하면 테이블에 더한 상태가 화면에는 보이는데 내려받기는 400이
     * 된다(MemberStatusCode.from이 모르는 코드를 null로 다루는 것과 같은 전제다). 순서를 요청이 아니라
     * 표시 순번으로 맞추는 것은 같은 선택이 같은 제목·같은 감사 줄이 되게 하기 위해서다.
     */
    private IncludedStatuses includedStatusesOf(List<String> requested) {
        Set<String> codes = new LinkedHashSet<>();
        if (requested != null) {
            for (String code : requested) {
                if (code != null && !code.isBlank()) {
                    codes.add(code.trim());
                }
            }
        }
        if (codes.isEmpty()) {
            return new IncludedStatuses(
                    List.of(MemberStatusCode.ENROLLED.code()), ENROLLED_ONLY_LABEL);
        }

        List<MemberStatusEntity> all =
                memberStatusRepository.findAllByOrderByDisplayOrderAscCodeAsc();
        Set<String> known =
                all.stream().map(MemberStatusEntity::getCode).collect(Collectors.toSet());
        List<String> unknown = codes.stream().filter(code -> !known.contains(code)).toList();
        if (!unknown.isEmpty()) {
            throw new GeneralException(
                    CommonErrorCode.INVALID_CODE_VALUE,
                    "기준 코드에 없는 회원 상태입니다: " + String.join(", ", unknown));
        }
        List<MemberStatusEntity> chosen =
                all.stream().filter(status -> codes.contains(status.getCode())).toList();
        return new IncludedStatuses(
                chosen.stream().map(MemberStatusEntity::getCode).toList(),
                statusLabelOf(chosen, all.size()));
    }

    /*
     * 제목 괄호(클래스 주석). 재학만이면 양식 원문 «재학생», 전부면 null(괄호 없음), 그 밖에는 고른 상태
     * 이름을 표시 순번대로 잇는다. chosen은 검사를 통과해 중복 없는 mbr_stts의 부분집합이므로 크기가
     * 같으면 전부다. 상태가 재학 하나뿐인 DB에서는 앞의 규칙이 이긴다.
     */
    static String statusLabelOf(List<MemberStatusEntity> chosen, int statusCount) {
        if (chosen.size() == 1
                && MemberStatusCode.ENROLLED.code().equals(chosen.get(0).getCode())) {
            return ENROLLED_ONLY_LABEL;
        }
        if (chosen.size() == statusCount) {
            return null;
        }
        return chosen.stream().map(MemberStatusEntity::getName).collect(Collectors.joining("·"));
    }

    /*
     * 회장 → 부회장 순의 행. 같은 역할 안에서는 학번 순이고, 한 사람이 둘 다면 회장 한 줄이다.
     * 회장이 한 명도 없을 수 있다 — 거절은 내려받기가 한다(클래스 주석).
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
        return officers;
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

    /* 포함할 상태 코드(표시 순번 순)와 제목 괄호에 적을 말(null이면 괄호 없음) */
    private record IncludedStatuses(List<String> codes, String label) {}

    /* select의 결과 — officers는 회장 → 부회장 순, others는 명부 순서다 */
    private record Selection(
            List<String> statusCodes,
            LocalDate today,
            Map<Long, MemberRosterRow> officers,
            List<MemberEntity> others,
            String title) {

        boolean hasPresident() {
            return officers.values().stream().anyMatch(row -> PRESIDENT.equals(row.position()));
        }
    }
}
