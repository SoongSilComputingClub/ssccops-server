package org.sscc.ssccopsserver.domain.member.service;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.sscc.ssccopsserver.domain.member.code.MemberImportField;
import org.sscc.ssccopsserver.domain.member.code.error.MemberErrorCode;
import org.sscc.ssccopsserver.domain.member.dto.MemberImportCsv;
import org.sscc.ssccopsserver.domain.member.dto.MemberImportCsvRow;
import org.sscc.ssccopsserver.domain.member.dto.MemberImportMapping;
import org.sscc.ssccopsserver.domain.member.dto.MemberImportPreviewResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberImportRowResult;
import org.sscc.ssccopsserver.domain.member.dto.MemberImportValidationResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberImportValidationResponse.MemberImportSummary;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/*
 * CSV 회원 이관 사전 검증 (#84).
 *
 * **@Transactional(readOnly = true)** — 두 엔드포인트 모두 아무것도 쓰지 않는다. 읽기 전용을
 * 명시하는 것은 실수로 저장이 끼어드는 것을 막기 위해서이기도 하고, 이 API가 "확인만 한다"는
 * 계약 자체이기 때문이다. 실제 등록은 #85가 별도 엔드포인트로 한다.
 */
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

    private final MemberImportParser parser;
    private final MemberImportValidator validator;
    private final MemberRepository memberRepository;
    private final MemberGradeRepository memberGradeRepository;
    private final MemberStatusRepository memberStatusRepository;
    private final ObjectMapper objectMapper;

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
