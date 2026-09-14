package org.sscc.ssccopsserver.domain.assistant.service;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.sscc.ssccopsserver.domain.assistant.code.RagDocumentFormat;
import org.sscc.ssccopsserver.domain.assistant.code.RagDocumentType;
import org.sscc.ssccopsserver.domain.assistant.code.error.AssistantErrorCode;
import org.sscc.ssccopsserver.domain.assistant.dto.RagDocumentUploadResponse;
import org.sscc.ssccopsserver.domain.assistant.entity.RagDocumentEntity;
import org.sscc.ssccopsserver.domain.assistant.repository.RagDocumentRepository;
import org.sscc.ssccopsserver.domain.file.code.FileTargetType;
import org.sscc.ssccopsserver.domain.file.service.FileReferenceService;
import org.sscc.ssccopsserver.domain.file.service.FileUploader;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.code.error.CommonErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/*
 * 규정 문서 업로드 (#399 · 기획안 §5.1 · §9 · §11).
 *
 * ══ 순서가 계약이다 — 파싱 → 행 → R2 PUT ═════════════════════════
 *
 * **파싱이 R2 PUT보다 먼저인 것이 요점이다.** 반대로 두면 파싱에 실패한 파일의 오브젝트가
 * 고아로 남는다 — **되돌릴 수 없는 쪽을 뒤로 미루는 것**이 `FileEraser`(#234)·`FileCopier`와
 * 같은 규칙이다. 파싱을 워커로 미루지 않는 것은, 그러면 `.md` 계약 위반이 «색인 실패»로만
 * 드러나고 화면에 미리보기가 없어 운영진이 무엇이 틀렸는지 즉시 알 수 없기 때문이다(§2).
 * 오래 걸리는 것은 임베딩뿐이고 그것만 뒤로 뺀다 — **이 메서드는 임베딩을 부르지 않는다.**
 *
 * **행 저장이 PUT보다 앞인 것은 키에 `ragDocId`가 들어가기 때문이다.** 계약이 말하는
 * «실패했을 때 아무것도 남지 않는다»는 그대로다 — 한 트랜잭션이라 PUT이 실패하면 행도 함께
 * 롤백되고(`FileUploader`가 커밋 뒤로 미루지 않는 이유), 파싱이 실패하면 그 전이라 행도 PUT도
 * 없다. 반대로 키에서 식별자를 빼면 «어느 문서의 원본인가»를 `file_rfrnc` 행으로만 알 수 있어
 * 버킷만 보고는 아무것도 찾을 수 없다.
 *
 * ══ 파싱 결과를 쓰지 않고 버린다 ═══════════════════════════════
 *
 * 여기서 파싱은 **검증**이다. 청크를 만들어 두었다가 워커에 넘기지 않는 것은 재색인의 재료가
 * 언제나 R2의 원본이기 때문이며(파서 규칙이 바뀌면 그때 다시 읽어야 한다), 한 번 더 읽는 비용은
 * 색인 한 번에 붙는 임베딩 수백 번 옆에서 보이지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagDocumentServiceImpl implements RagDocumentService {

    /*
     * 업로드 상한 10MB. **서블릿 설정(`spring.servlet.multipart.max-file-size` 16MB)이 아니라
     * 여기서 끊는다** — 서블릿 계층이 먼저 걸러 버리면 도메인 오류 코드가 붙지 않은 응답이 나가고
     * 화면이 무엇이 잘못됐는지 안내하지 못한다(#84가 CSV 5MB에서 쓴 두 겹 그대로).
     */
    private static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024;

    /*
     * 적재 레이트 리밋 — **회원당 일 10회**(기획안 §11).
     *
     * 질의 한도와 따로 두는 것은 **적재 한 건이 나중에 임베딩을 수백 번 부르기 때문**이다
     * (1.2MB PDF 한 건이 184청크). 무료 쿼터는 API 키 단위의 공유 자원이라 한 사람이 태우면
     * 모두가 답을 못 받는다.
     */
    private static final int MAX_UPLOADS_PER_DAY = 10;

    /*
     * `doc_cd`의 모양. **어휘는 강제하지 않는다** — `REGULATION`·`SCHOOL_RULE`은 운영 규칙이고
     * 표준코드 그룹으로 등재하지 않았다(ssccops#325 · 엔티티 주석). 여기서 보는 것은 모양뿐이며,
     * 그 이유는 이 값이 판본을 묶는 열쇠라 **눈으로 같아 보이는 두 값이 다른 문서가 되면 안 되기**
     * 때문이다. 공백·한글·하이픈을 섞어 받으면 `회칙`과 `회칙 `이 각자 1판본으로 자란다.
     */
    private static final Pattern DOCUMENT_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,19}");

    /** `doc_nm`·`orgnl_file_nm` 컬럼 길이. 조용히 자르지 않고 400으로 돌려보낸다 */
    private static final int MAX_NAME_LENGTH = 200;

    private final AssistantFeature assistantFeature;
    private final RegulationParser regulationParser;
    private final GenericTextExtractor genericTextExtractor;
    private final RagDocumentRepository ragDocumentRepository;
    private final FileReferenceService fileReferenceService;
    private final FileUploader fileUploader;
    private final Clock clock;

    @Override
    @Transactional
    public RagDocumentUploadResponse upload(
            MultipartFile file, String documentCode, String name, MemberEntity registrant) {

        assistantFeature.requireEnabled();

        String code = normalizeDocumentCode(documentCode);
        String originalFileName = requireFileName(file);
        RagDocumentFormat format = RagDocumentFormat.fromFileName(originalFileName);
        byte[] content = readContent(file);

        /*
         * 한도를 파싱 앞에서 본다 — Tika 파싱이 이 메서드에서 가장 비싼 일이고, 막을 요청이라면
         * 그 비용을 치르기 전에 막는 편이 한도를 둔 목적(자원 보호)에 맞다.
         */
        requireWithinDailyQuota(registrant);

        parse(format, content, originalFileName);

        short version =
                ragDocumentRepository
                        .findMaxVersion(code)
                        .map(previous -> (short) (previous + 1))
                        .orElse(RagDocumentEntity.FIRST_VERSION);

        /*
         * **식별자를 먼저 받는다** — 키가 `rag-documents/{ragDocId}/…`라서다. 같은 `doc_cd`에
         * 동시 업로드가 겹치면 둘이 같은 번호를 집어 `uk_rag_doc_doc_cd_ver`에 걸리는데, 그때는
         * 둘째 요청이 500으로 떨어지고 아무것도 남지 않는다 — 운영진이 다시 올리면 된다. 잠금을
         * 걸지 않은 것은 첫 판본에는 잠글 행이 없어(같은 이유로 #401의 시행본 판정도 그렇다)
         * 반쪽짜리 방어가 되고, 일 10회 한도 아래에서 실제로 겹칠 일이 없기 때문이다.
         */
        RagDocumentEntity document =
                ragDocumentRepository.saveAndFlush(
                        RagDocumentEntity.register(
                                code,
                                resolveName(name, originalFileName, format),
                                format.getDocumentType(),
                                version,
                                originalFileName,
                                content.length,
                                registrant));

        /*
         * 키를 조립하는 자리는 여기 한 곳이다. **원본 파일명을 키에 쓰지 않는다** — 같은 버킷에
         * 얼굴이 찍힌 출석 인증사진이 있으므로(ssccops#156) `../`가 낀 파일명이 키가 되면 그것이
         * 곧 남의 사진이다. 확장자도 파일명이 아니라 형식 표의 값을 붙인다.
         */
        String objectKey =
                FileTargetType.RAG_DOCUMENT.getObjectKeyPrefix()
                        + "%d/%s%s"
                                .formatted(
                                        document.getId(), UUID.randomUUID(), format.getExtension());

        fileUploader.upload(objectKey, content, format.getContentType());
        fileReferenceService.upsert(FileTargetType.RAG_DOCUMENT, document.getId(), objectKey);

        log.info(
                "규정 문서 업로드 — ragDocId={} docCd={} 판본={} 형식={} 크기={}바이트",
                document.getId(),
                code,
                version,
                format,
                content.length);

        return RagDocumentUploadResponse.from(document);
    }

    /*
     * 유형이 파서를 고른다(§5.2). **결과는 버린다** — 여기서 파싱은 검증이고, 색인은 R2의 원본을
     * 다시 읽는다(클래스 주석).
     */
    private void parse(RagDocumentFormat format, byte[] content, String fileName) {
        if (format.getDocumentType() == RagDocumentType.STRUCTURED) {
            // 바이트를 그대로 넘긴다 — UTF-8 디코딩과 BOM 제거는 파서의 계약이고(#400), 여기서
            // 한 번 더 하면 검증과 색인이 같은 파일을 다르게 읽을 자리가 생긴다
            regulationParser.parse(content);
            return;
        }
        genericTextExtractor.extract(content, fileName);
    }

    /*
     * 오늘 올린 건수로 센다 — 하루의 경계는 서비스 표준 시간대(Asia/Seoul)이고 그 값은 주입된
     * `Clock`에서 온다(AP-12 · 테스트가 고정할 수 있는 유일한 방법이기도 하다).
     */
    private void requireWithinDailyQuota(MemberEntity registrant) {
        Instant startOfToday = LocalDate.now(clock).atStartOfDay(clock.getZone()).toInstant();
        long today =
                ragDocumentRepository.countByRegistrantIdAndCreatedAtGreaterThanEqual(
                        registrant.getId(), startOfToday);

        if (today >= MAX_UPLOADS_PER_DAY) {
            throw new GeneralException(
                    AssistantErrorCode.ASSISTANT_RATE_LIMITED,
                    "하루에 올릴 수 있는 문서는 %d건입니다. 내일 다시 올려 주세요.".formatted(MAX_UPLOADS_PER_DAY));
        }
    }

    private static String normalizeDocumentCode(String documentCode) {
        String code = documentCode == null ? "" : documentCode.trim().toUpperCase(Locale.ROOT);
        if (!DOCUMENT_CODE.matcher(code).matches()) {
            throw new GeneralException(
                    CommonErrorCode.VALIDATION_FAILED,
                    "문서 코드(documentCode)는 영문 대문자로 시작하는 20자 이내의 영문·숫자·밑줄이어야 합니다.");
        }
        return code;
    }

    /*
     * 표시명의 기본값은 **파일명에서 확장자를 뗀 것**이다(#399). 운영진이 나중에 화면에서 고치며,
     * 그 값은 `GENERIC` 청크의 맨 앞에 붙으므로(#398) 재색인 때 다시 찍힌다.
     */
    private static String resolveName(
            String name, String originalFileName, RagDocumentFormat format) {

        String trimmed = name == null ? "" : name.trim();
        String resolved =
                trimmed.isEmpty()
                        ? originalFileName.substring(
                                0, originalFileName.length() - format.getExtension().length())
                        : trimmed;

        if (resolved.isBlank() || resolved.length() > MAX_NAME_LENGTH) {
            throw new GeneralException(
                    CommonErrorCode.VALIDATION_FAILED,
                    "문서 표시명(name)은 1자 이상 %d자 이하여야 합니다.".formatted(MAX_NAME_LENGTH));
        }
        return resolved;
    }

    /*
     * 파일명은 형식을 정하는 값이자(§5.2) 그대로 `orgnl_file_nm`에 남는 값이다. 없는 요청은
     * 확장자를 볼 수 없어 형식 판정 자체가 성립하지 않는다.
     */
    private static String requireFileName(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new GeneralException(CommonErrorCode.VALIDATION_FAILED, "업로드할 파일(file)이 없습니다.");
        }
        String fileName = file.getOriginalFilename();
        if (fileName == null || fileName.isBlank() || fileName.length() > MAX_NAME_LENGTH) {
            throw new GeneralException(
                    CommonErrorCode.VALIDATION_FAILED,
                    "파일 이름은 1자 이상 %d자 이하여야 합니다.".formatted(MAX_NAME_LENGTH));
        }
        return fileName;
    }

    /*
     * **신고한 크기가 아니라 실제 바이트로 끊는다.** `MultipartFile.getSize()`는 이미 서블릿이
     * 받아 둔 값이라 거짓일 수 없지만, 어차피 파싱하려면 전부 읽어야 하므로 판정도 읽은 것으로
     * 한다 — 두 값이 갈릴 자리를 만들지 않는다.
     */
    private static byte[] readContent(MultipartFile file) {
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new GeneralException(AssistantErrorCode.RAG_DOCUMENT_TOO_LARGE, tooLarge());
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException exception) {
            log.warn("규정 문서 업로드 본문을 읽지 못했다", exception);
            throw new GeneralException(
                    AssistantErrorCode.RAG_DOCUMENT_PARSE_FAILED, "파일을 읽지 못했습니다. 다시 올려 주세요.");
        }
        if (content.length > MAX_FILE_SIZE_BYTES) {
            throw new GeneralException(AssistantErrorCode.RAG_DOCUMENT_TOO_LARGE, tooLarge());
        }
        return content;
    }

    private static String tooLarge() {
        return "파일은 %dMB까지 올릴 수 있습니다.".formatted(MAX_FILE_SIZE_BYTES / 1024 / 1024);
    }
}
