package org.sscc.ssccopsserver.domain.member.service;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.code.MemberImportField;
import org.sscc.ssccopsserver.domain.member.dto.MemberImportCsvRow;
import org.sscc.ssccopsserver.domain.member.dto.MemberImportMapping;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberGradeEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberStatusEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;

import lombok.RequiredArgsConstructor;

/*
 * CSV 이관에서 **한 행을 mbr에 넣는 자리** (#85 · BR-M45).
 *
 * ══ 왜 별도 빈이고 왜 REQUIRES_NEW인가 ══════════════════════════════
 * 이관은 행 단위다 — 128건 중 6건이 잘못됐다고 전체가 막히면 운영이 시작조차 못 한다. 그런데
 * **하나의 @Transactional 안에서 예외를 잡아 계속 진행하면 안 된다**: JPA는 예외가 난 시점에
 * 영속성 컨텍스트가 오염되고(EntityManager가 더는 쓸 수 없는 상태가 된다) 이후 flush가 전부
 * 깨진다. 게다가 참여 중인 트랜잭션은 rollback-only로 표시되어, 성공한 앞의 127건까지 커밋
 * 시점에 통째로 되돌아간다.
 *
 * 그래서 행마다 트랜잭션을 새로 연다. REQUIRES_NEW는 **자기 호출(this.method())로는 걸리지
 * 않으므로** 이 클래스가 서비스와 분리된 별도 빈이어야 한다 — 프록시를 거쳐야 애노테이션이 산다.
 * 서비스가 같은 클래스에 이 메서드를 두고 부르면 애노테이션은 그대로 있는데 트랜잭션 경계만
 * 조용히 사라져, 위에 적은 오염이 그대로 재현된다.
 *
 * 회원 INSERT와 최초 이력 두 건은 **같은 트랜잭션**이다. 회원만 남고 이력이 없으면 그 회원의
 * "언제 무엇으로 시작했는지"를 영영 알 수 없으므로, 나누는 것은 행과 행 사이뿐이다.
 *
 * ══ 이관은 가입이 아니다 ═══════════════════════════════════════════
 * 등급은 CSV 값 그대로 넣고 TEMP로 고정하지 않는다(명부의 정회원을 임시회원으로 만들면 그 사람의
 * 이력이 거짓이 된다), 상태도 CSV 그대로이며 탈퇴·제명도 허용한다(isSignupSelectable()을 이
 * 경로에 적용하지 않는다 — 과거 명부에는 이미 떠난 사람이 들어 있다), auth_user_id는 채우지
 * 않는다(아직 로그인한 적 없는 회원이다). MemberServiceImpl.signUp과 규칙이 정반대라 같은
 * 메서드에 합치지 않고 경로를 나눈 것이다.
 */
@Component
@RequiredArgsConstructor
public class MemberImportRowExecutor {

    // 이력만 봐도 본인 가입이 아니라 운영자의 명부 이관임을 알 수 있어야 한다
    static final String IMPORT_HISTORY_REASON = "CSV 이관";

    // 기수 미입력은 0(미배정)이다. **학번으로 추정하지 않는다** (BR-M43) — 가입 경로와 같은 센티널
    private static final int UNASSIGNED_GENERATION_NUMBER = 0;

    private final MemberRepository memberRepository;
    private final MemberGradeRepository memberGradeRepository;
    private final MemberStatusRepository memberStatusRepository;
    private final MemberInitialHistoryRecorder initialHistoryRecorder;

    /*
     * 검증을 통과한 행 하나를 등록하고 새 mbr_id를 돌려준다.
     *
     * 값의 규칙은 여기서 다시 보지 않는다 — 부르는 쪽이 #84의 MemberImportValidator로 이미 봤고,
     * 규칙이 두 벌이 되면 검증에서 통과한 행이 실행에서 막힌다. 여기서 하는 일은 통과한 값을
     * 데이터사전의 컬럼으로 옮기는 것뿐이다.
     *
     * 등급·상태는 기준 코드 테이블을 조회하지 않고 getReferenceById로 프록시만 만든다 — 코드는
     * 이미 reference가 명칭에서 되돌려 준 값이라 실재가 보장되고, FK 컬럼을 채우는 데는 식별자면
     * 충분하다. 행마다 조회하면 128건 명부가 등급·상태·운영자 조회만 384번이 된다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long create(
            MemberImportCsvRow row,
            MemberImportMapping mapping,
            MemberImportReferenceData reference,
            Long operatorId,
            LocalDate importDate) {

        MemberGradeEntity grade =
                memberGradeRepository.getReferenceById(
                        codeOf(
                                reference.gradeCodeOf(
                                        mapping.valueOf(MemberImportField.GRADE_NAME, row)),
                                "회원 등급"));
        MemberStatusEntity status =
                memberStatusRepository.getReferenceById(
                        codeOf(
                                reference.statusCodeOf(
                                        mapping.valueOf(MemberImportField.STATUS_NAME, row)),
                                "회원 상태"));

        // 가입일은 CSV 값이고, 미입력이면 이관일이다(주입된 Clock에서 온 값을 부르는 쪽이 넘긴다)
        LocalDate joinDate =
                parseDateOrDefault(mapping.valueOf(MemberImportField.JOIN_DATE, row), importDate);

        MemberEntity member =
                MemberEntity.create(
                        /*
                         * 학번 미입력은 빈 문자열이 아니라 **NULL**이다 — uk_mbr_student_number가
                         * 살아 있어 빈 문자열로 채우면 두 번째 졸업 회원부터 UNIQUE 충돌이 난다.
                         */
                        trimToNull(mapping.valueOf(MemberImportField.STUDENT_NUMBER, row)),
                        parseIntOrDefault(
                                mapping.valueOf(MemberImportField.GENERATION_NUMBER, row),
                                UNASSIGNED_GENERATION_NUMBER),
                        mapping.valueOf(MemberImportField.MEMBER_NAME, row),
                        trimToNull(mapping.valueOf(MemberImportField.DEPARTMENT_NAME, row)),
                        parseInteger(mapping.valueOf(MemberImportField.ACADEMIC_YEAR, row)),
                        trimToNull(mapping.valueOf(MemberImportField.PHONE_NUMBER, row)),
                        /*
                         * 이메일은 명부의 값이 유일한 출처다. 가입은 소셜 계정에서 받아 오지만
                         * 이관 회원에게는 아직 연결된 계정이 없다.
                         */
                        trimToNull(mapping.valueOf(MemberImportField.EMAIL, row)),
                        grade,
                        status,
                        joinDate);
        // auth_user_id는 채우지 않는다 — 아직 로그인한 적 없는 회원이다 (계정 연결은 #86의 몫)

        MemberEntity saved = memberRepository.saveAndFlush(member);

        /*
         * 최초 이력의 변경자는 **요청한 운영자**다(가입은 본인). 그래야 "이 사람 등급은 누가
         * 정했나"에 답할 수 있다. 운영자 엔티티는 인증 필터가 준 준영속 객체를 쓰지 않고 이
         * 트랜잭션의 프록시로 다시 만든다 — 준영속 엔티티를 그대로 연관에 물리면 이 영속성
         * 컨텍스트가 모르는 인스턴스라 병합이 끼어든다.
         */
        initialHistoryRecorder.record(
                saved,
                grade,
                status,
                joinDate,
                IMPORT_HISTORY_REASON,
                memberRepository.getReferenceById(operatorId));

        return saved.getId();
    }

    private static String codeOf(Optional<String> code, String what) {
        /*
         * 검증을 통과한 행이라 언제나 값이 있다. 그래도 orElseThrow로 두는 것은, 언젠가 검증을
         * 건너뛰고 이 메서드를 부르는 경로가 생기면 조용히 NULL FK로 흘러가지 않게 하기 위해서다.
         */
        return code.orElseThrow(
                () -> new IllegalStateException("검증을 통과한 행의 %s 코드를 찾을 수 없습니다.".formatted(what)));
    }

    private static LocalDate parseDateOrDefault(String raw, LocalDate defaultValue) {
        return raw.isBlank() ? defaultValue : LocalDate.parse(raw);
    }

    private static int parseIntOrDefault(String raw, int defaultValue) {
        Integer parsed = parseInteger(raw);
        return parsed == null ? defaultValue : parsed;
    }

    private static Integer parseInteger(String raw) {
        return raw.isBlank() ? null : Integer.valueOf(raw.trim());
    }

    private static String trimToNull(String value) {
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
