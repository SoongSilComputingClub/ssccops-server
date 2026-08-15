package org.sscc.ssccopsserver.domain.member.service;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.sscc.ssccopsserver.domain.member.code.MemberImportExecutionStatus;
import org.sscc.ssccopsserver.domain.member.code.MemberImportField;
import org.sscc.ssccopsserver.domain.member.code.MemberImportRowStatus;
import org.sscc.ssccopsserver.domain.member.code.error.MemberErrorCode;
import org.sscc.ssccopsserver.domain.member.dto.MemberImportCsv;
import org.sscc.ssccopsserver.domain.member.dto.MemberImportCsvRow;
import org.sscc.ssccopsserver.domain.member.dto.MemberImportExecutionResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberImportExecutionResponse.MemberImportExecutionSummary;
import org.sscc.ssccopsserver.domain.member.dto.MemberImportExecutionRow;
import org.sscc.ssccopsserver.domain.member.dto.MemberImportMapping;
import org.sscc.ssccopsserver.domain.member.dto.MemberImportPreviewResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberImportRowResult;
import org.sscc.ssccopsserver.domain.member.dto.MemberImportRowResult.MemberImportRowIssue;
import org.sscc.ssccopsserver.domain.member.dto.MemberImportValidationResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberImportValidationResponse.MemberImportSummary;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/*
 * CSV 회원 이관 — 사전 검증(#84)과 실행(#85).
 *
 * ══ 트랜잭션 경계가 세 메서드에서 서로 다르다 ═══════════════════════
 * - preview·validate: **@Transactional(readOnly = true)**. 아무것도 쓰지 않는다는 것이 두
 *   엔드포인트의 계약 자체이며(테스트가 mbr 행 수로 못 박는다), 읽기 전용을 명시해 나중에 저장이
 *   끼어드는 것을 막는다.
 * - importMembers: **@Transactional이 아예 없다.** 하나로 묶으면 한 행의 실패가 128건 전부를
 *   되돌리고, 예외를 잡아 계속 진행하려 해도 JPA의 영속성 컨텍스트가 이미 오염돼 이후 flush가
 *   전부 깨진다. 경계는 행마다 MemberImportRowExecutor가 REQUIRES_NEW로 연다(그 클래스 주석에
 *   자세히 적어 두었다). 여기서 하는 일은 파일을 읽고 행을 나눠 그쪽으로 넘기는 것뿐이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberImportServiceImpl implements MemberImportService {

    /*
     * 이관 파일 상한 5MB. 서블릿 설정(spring.servlet.multipart)이 아니라 여기서 끊는 것은
     * 응답이 INVALID_CSV_FILE이어야 하기 때문이다 — 서블릿 계층에서 걸리면 도메인 오류 코드가
     * 붙지 않은 응답이 나가고 위저드가 무엇이 잘못됐는지 안내하지 못한다.
     */
    private static final long MAX_FILE_SIZE_BYTES = 5L * 1024 * 1024;

    private static final String CSV_EXTENSION = ".csv";
    private static final String FILE_TOKEN_PREFIX = "sha256:";
    private static final String HASH_ALGORITHM = "SHA-256";

    /*
     * 저장 도중 실패한 행의 사유. 예외 메시지를 그대로 내리지 않는 것은 제약명·SQL 조각 같은
     * 내부 사정이 화면으로 새어 나가기 때문이다 — 원인은 로그에 남긴다.
     */
    private static final String UNEXPECTED_FAILURE_REASON = "이관 도중 오류가 발생했습니다.";

    // 검증 시점 스냅숏에는 없었지만 저장 순간 UNIQUE에 걸린 학번. 결과는 건너뛴 것과 같다
    private static final String CONCURRENT_DUPLICATE_REASON = "이미 등록된 학번";

    private final MemberImportParser parser;
    private final MemberImportValidator validator;

    /*
     * 행 하나를 넣는 자리. **반드시 별도 빈이어야 한다** — REQUIRES_NEW는 프록시를 거쳐야 살아
     * 있고, 이 클래스의 private 메서드로 옮기면 애노테이션만 남고 경계는 사라진다.
     */
    private final MemberImportRowExecutor rowExecutor;

    private final MemberRepository memberRepository;
    private final MemberGradeRepository memberGradeRepository;
    private final MemberStatusRepository memberStatusRepository;
    private final ObjectMapper objectMapper;

    // 가입일이 비어 있는 행에 넣을 이관일. 테스트에서 고정할 수 있도록 주입받는다 (ClockConfig)
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public MemberImportPreviewResponse preview(MultipartFile file) {
        return MemberImportPreviewResponse.from(parser.parse(readContent(file)));
    }

    @Override
    @Transactional(readOnly = true)
    public MemberImportValidationResponse validate(MultipartFile file, String mappingJson) {
        byte[] content = readContent(file);
        MemberImportCsv csv = parser.parse(content);
        MemberImportMapping mapping = MemberImportMapping.of(parseMapping(mappingJson), csv);

        MemberImportReferenceData reference = loadReferenceData(csv, mapping);

        List<MemberImportRowResult> rows =
                csv.rows().stream()
                        .map(row -> validator.validate(row, mapping, reference))
                        .toList();

        return new MemberImportValidationResponse(
                fileTokenOf(content), MemberImportSummary.of(rows), rows);
    }

    /*
     * 이관 실행 (#85). 검증을 **다시 돌린 뒤** 통과한 행만 넣는다.
     *
     * 검증 결과를 요청에 실어 받지 않고 다시 계산하는 것이 요점이다 — 클라이언트가 보낸 판정을
     * 믿으면 "이 행은 OK다"라고 주장하는 것만으로 규칙을 우회할 수 있고, 검증 이후 다른 운영자가
     * 같은 학번을 넣었을 수도 있다. fileToken이 같으므로 파일은 같고, 규칙도 #84의 파서·검증기
     * 그대로라 판정이 갈릴 자리는 그 사이에 바뀐 DB뿐이다(그게 바로 다시 봐야 하는 이유다).
     *
     * **이 메서드에 @Transactional이 없는 것은 실수가 아니다.** 클래스 주석 참고.
     */
    @Override
    public MemberImportExecutionResponse importMembers(
            MultipartFile file, String mappingJson, String fileToken, Long operatorId) {

        byte[] content = readContent(file);
        verifyFileToken(content, fileToken);

        MemberImportCsv csv = parser.parse(content);
        MemberImportMapping mapping = MemberImportMapping.of(parseMapping(mappingJson), csv);
        MemberImportReferenceData reference = loadReferenceData(csv, mapping);

        LocalDate importDate = LocalDate.now(clock);
        List<MemberImportExecutionRow> rows = new ArrayList<>(csv.rows().size());

        /*
         * 재실행 시 또 들어갈 행의 수. 학번이 없는 행은 중복이라고 판정할 근거가 아예 없어
         * 두 번째 실행에서도 그대로 등록된다 — 이 API가 멱등하지 않다는 사실을 응답으로 드러낸다
         * (MemberImportExecutionSummary 주석 참고).
         */
        int reimportDuplicates = 0;

        for (MemberImportCsvRow row : csv.rows()) {
            MemberImportExecutionRow result =
                    executeRow(row, mapping, reference, operatorId, importDate);
            rows.add(result);

            if (result.status() == MemberImportExecutionStatus.CREATED
                    && mapping.valueOf(MemberImportField.STUDENT_NUMBER, row).isBlank()) {
                reimportDuplicates++;
            }
        }

        return new MemberImportExecutionResponse(
                MemberImportExecutionSummary.of(rows, reimportDuplicates), rows);
    }

    /*
     * 한 행의 판정과 등록. **여기서 규칙을 새로 적지 않는다** — 판정은 #84의 MemberImportValidator가
     * 그대로 하고, 이 메서드는 그 결과를 실행 어휘(CREATED·SKIPPED·FAILED)로 옮길 뿐이다. 규칙이
     * 두 벌이 되면 검증 화면에서 통과한 행이 실행에서 막힌다.
     *
     * 중복(DUPLICATE)은 건너뛴다. **덮어쓰지 않는다** (BR-M40) — 자동 병합은 없고 어느 쪽이 맞는
     * 행인지는 운영자가 판단할 일이다.
     *
     * 예외를 여기서 잡는 것이 안전한 것은 rowExecutor가 REQUIRES_NEW로 자기 트랜잭션을 열고 닫기
     * 때문이다. 이 메서드에는 트랜잭션이 없으므로 잡아도 오염될 영속성 컨텍스트가 없다.
     */
    private MemberImportExecutionRow executeRow(
            MemberImportCsvRow row,
            MemberImportMapping mapping,
            MemberImportReferenceData reference,
            Long operatorId,
            LocalDate importDate) {

        MemberImportRowResult validation = validator.validate(row, mapping, reference);
        long rowNo = validation.rowNo();
        String target = validation.target();

        if (validation.status() == MemberImportRowStatus.ERROR) {
            return MemberImportExecutionRow.failed(rowNo, target, reasonOf(validation));
        }
        if (validation.status() == MemberImportRowStatus.DUPLICATE) {
            return MemberImportExecutionRow.skipped(rowNo, target, reasonOf(validation));
        }

        try {
            Long memberId = rowExecutor.create(row, mapping, reference, operatorId, importDate);
            return MemberImportExecutionRow.created(rowNo, target, memberId);

        } catch (DataIntegrityViolationException ex) {
            /*
             * 검증은 요청 시작 시점의 학번 스냅숏으로 본다. 그 사이 다른 요청이 같은 학번을 넣으면
             * uk_mbr_student_number 위반으로만 드러나는데, 결과는 선조회로 걸린 중복과 같으므로
             * 오류가 아니라 SKIPPED로 내린다 — 운영자가 할 일이 없다는 점까지 같다.
             */
            log.info("CSV 이관 {}행이 제약 위반으로 건너뛰어졌습니다.", rowNo, ex);
            return MemberImportExecutionRow.skipped(rowNo, target, CONCURRENT_DUPLICATE_REASON);

        } catch (RuntimeException ex) {
            log.warn("CSV 이관 {}행 등록에 실패했습니다.", rowNo, ex);
            return MemberImportExecutionRow.failed(rowNo, target, UNEXPECTED_FAILURE_REASON);
        }
    }

    /*
     * 행별 사유를 한 문장으로 합친다. 검증 응답은 사유를 목록으로 내리지만 실행 결과는 줄마다 한
     * 칸이라("143행 · 서지훈 · 필수값 누락 · 회원명") 문자열이어야 한다.
     *
     * 필드는 key가 아니라 사람이 읽는 이름으로 적는다 — 128건짜리 결과에서 'mbrNm'을 다시 해석하게
     * 하지 않기 위해서다.
     */
    private static String reasonOf(MemberImportRowResult validation) {
        return validation.reasons().stream()
                .map(MemberImportServiceImpl::describeIssue)
                .collect(Collectors.joining(", "));
    }

    private static String describeIssue(MemberImportRowIssue issue) {
        return MemberImportField.labelOfKey(issue.field())
                .map(label -> "%s · %s".formatted(issue.message(), label))
                .orElse(issue.message());
    }

    /*
     * 검증한 파일과 넣는 파일이 같은지 확인한다 (BR-M48).
     *
     * 값이 비어 있는 것도 불일치로 본다 — 토큰을 빼면 검사를 건너뛸 수 있다면 검사가 아니다.
     * 비교는 대소문자를 가리지 않는다(해시를 헥사로 옮기는 방식이 클라이언트마다 다르다).
     */
    private static void verifyFileToken(byte[] content, String fileToken) {
        if (fileToken == null || !fileTokenOf(content).equalsIgnoreCase(fileToken.trim())) {
            throw new GeneralException(MemberErrorCode.IMPORT_FILE_MISMATCH);
        }
    }

    /*
     * 파일 자체의 제약(형식·크기·읽기 가능 여부)을 파싱 **이전에** 본다. 5MB짜리 잘못된 파일을
     * 끝까지 파싱하고 나서 거절할 이유가 없다.
     *
     * 확장자와 콘텐츠 타입 중 하나만 맞아도 통과시킨다 — 윈도우에서 올린 CSV의 콘텐츠 타입은
     * 브라우저·OS에 따라 text/csv·application/vnd.ms-excel·application/octet-stream으로 제각각이라,
     * 타입만 보면 정상 파일이 거절된다. 반대로 확장자만 보면 이름만 바꾼 파일이 통과하는데,
     * 그건 파서가 INVALID_CSV_FILE로 걸러 준다.
     */
    private static byte[] readContent(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new GeneralException(MemberErrorCode.EMPTY_CSV_FILE);
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new GeneralException(MemberErrorCode.INVALID_CSV_FILE);
        }
        if (!isCsv(file)) {
            throw new GeneralException(MemberErrorCode.INVALID_CSV_FILE);
        }
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new GeneralException(MemberErrorCode.INVALID_CSV_FILE);
        }
    }

    private static boolean isCsv(MultipartFile file) {
        String filename = file.getOriginalFilename();
        if (filename != null && filename.toLowerCase(Locale.ROOT).endsWith(CSV_EXTENSION)) {
            return true;
        }
        String contentType = file.getContentType();
        return contentType != null && contentType.toLowerCase(Locale.ROOT).contains("csv");
    }

    /*
     * mapping은 multipart 파트로 오는 JSON 문자열이다. 본문 전체가 JSON이 아니라 파일과 함께 와야
     * 해서 @RequestBody로 받을 수 없고, 그래서 파싱을 여기서 한다. 형식이 깨진 값은 매핑이 성립하지
     * 않는 것과 같은 CSV_MAPPING_INVALID다 — 운영자가 할 일이 "매핑을 다시 고른다"로 같다.
     *
     * 값이 통째로 비어 있으면 빈 매핑으로 본다. 그 경우 필수 필드 검사에서 걸려 같은 코드로 나간다.
     */
    private Map<String, String> parseMapping(String mappingJson) {
        if (mappingJson == null || mappingJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(mappingJson, new TypeReference<Map<String, String>>() {});
        } catch (IOException ex) {
            throw new GeneralException(MemberErrorCode.CSV_MAPPING_INVALID);
        }
    }

    /*
     * 검증에 필요한 기준 데이터를 한 번에 모은다 — 등급 1회 + 상태 1회 + 학번 1회, 행 수와 무관하게
     * 3회다. 행마다 조회하면 128건 명부가 384번의 질의가 된다.
     */
    private MemberImportReferenceData loadReferenceData(
            MemberImportCsv csv, MemberImportMapping mapping) {

        Set<String> studentNumbers = new LinkedHashSet<>();
        Set<String> repeated = new LinkedHashSet<>();
        for (MemberImportCsvRow row : csv.rows()) {
            String studentNumber = mapping.valueOf(MemberImportField.STUDENT_NUMBER, row);
            if (!studentNumber.isBlank() && !studentNumbers.add(studentNumber)) {
                // 두 번째로 나타난 순간 그 학번은 겹친 것이고, 첫 행도 함께 중복이 된다
                repeated.add(studentNumber);
            }
        }

        Set<String> existing =
                studentNumbers.isEmpty()
                        ? Set.of()
                        : new HashSet<>(memberRepository.findStudentNumbersIn(studentNumbers));

        return MemberImportReferenceData.of(
                memberGradeRepository.findAll(),
                memberStatusRepository.findAll(),
                existing,
                repeated);
    }

    /*
     * 파일 내용의 SHA-256. 실행 API(#85)가 이 값을 되돌려 주게 해서 검증한 파일과 넣는 파일이
     * 같은 것임을 확인한다. 원본 바이트를 그대로 해싱하는 것은 BOM 제거·인코딩 해석 같은 서버의
     * 처리가 바뀌어도 같은 파일이 같은 값을 갖게 하기 위해서다.
     */
    private static String fileTokenOf(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance(HASH_ALGORITHM).digest(content);
            return FILE_TOKEN_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256은 모든 JVM이 제공해야 하는 알고리즘이라 도달할 수 없다
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", ex);
        }
    }
}
