package org.sscc.ssccopsserver.domain.academicprogram.service;

import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.academicprogram.code.error.AcademicProgramErrorCode;
import org.sscc.ssccopsserver.domain.academicprogram.dto.FileReferenceUploadRequest;
import org.sscc.ssccopsserver.domain.academicprogram.dto.FileReferenceUploadResponse;
import org.sscc.ssccopsserver.domain.academicprogram.entity.FileReferenceEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.SessionEntity;
import org.sscc.ssccopsserver.domain.academicprogram.repository.FileReferenceRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.SessionRepository;
import org.sscc.ssccopsserver.domain.event.code.EventImageType;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/*
 * 출석 인증사진 업로드 URL 발급 (#137 · 학술관리_API설계.md §3.5).
 *
 * **이 서비스도 파일을 받지 않는다.** 하는 일은 "이 회차에 이 형식의 사진을 하나 올려도 좋다"는
 * 허가를 짧은 유효기간의 서명된 URL로 내주는 것뿐이고, 실제 바이트는 브라우저에서 R2로 직접
 * 간다 — wave2 행사 이미지(#161 · EventImageServiceImpl)가 세운 구조를 그대로 따른다. 멀티파트
 * 업로드를 만들지 않는 것은 취향이 아니라 배포 환경의 제약이다(512MB 컨테이너, #107).
 *
 * **#161과 갈리는 곳은 하나다 — 여기는 DB에 행을 남긴다.** 데이터모델(§2)이 file_rfrnc를
 * 요구하기 때문이고(회차 상세가 사진 유무를 그 행으로 답한다), 그래서 이 서비스는 readOnly가
 * 아니다. 행이 실제 업로드보다 먼저 태어난다는 사실이 만드는 성질은 FileReferenceEntity 주석에
 * 적어 두었다.
 *
 * 재업로드는 UPSERT다(설계 결정 #1) — 기존 참조를 거절하지 않고 새 주소로 갈아 끼운다.
 * 그래서 클라이언트는 실패한 업로드를 같은 요청 한 번으로 다시 시도할 수 있다.
 */
@Service
@Transactional(readOnly = true)
public class SessionFileReferenceServiceImpl implements SessionFileReferenceService {

    /*
     * presigned PUT URL의 유효기간. **이 URL은 그 자체로 남의 버킷에 쓸 수 있는 권한**이라
     * 짧아야 한다 — 사진 한 장을 고른 직후 올리는 데 필요한 시간이면 충분하고, 길게 두면
     * 어딘가에 새어 나간 URL이 그만큼 오래 살아 있다(#161과 같은 값·같은 근거).
     */
    private static final Duration UPLOAD_URL_TTL = Duration.ofMinutes(10);

    private final SessionRepository sessionRepository;
    private final FileReferenceRepository fileReferenceRepository;
    private final SessionCorrectionPolicy sessionCorrectionPolicy;
    private final S3Presigner r2Presigner;
    private final String bucketName;

    /*
     * 미리보기 주소를 만드는 자리 (#200). 읽기 서명은 회차 상세와 한 곳에서만 만든다 — TTL과
     * 버킷이 두 경로에서 갈리면 "상세에서는 열리는데 업로드 직후에는 안 열린다"가 된다.
     */
    private final SessionFileReferenceViewer sessionFileReferenceViewer;

    /*
     * **공개 읽기 도메인 설정을 받지 않는다** (#200). 학술 인증사진은 비공개 버킷 + 서명된 URL로
     * 오가므로 계정 엔드포인트와 키만 있으면 업로드도 조회도 성립한다 — 붙들고 있으면 값이 없거나
     * 잘못된 환경에서 학술 기능이 통째로 뜨지 못한다.
     *
     * 마지막까지 그 설정을 쓰던 행사 본문 이미지(#161)도 같은 방식으로 옮겨 가면서
     * `r2.public-base-url` 자체가 사라졌다 (#208) — 공개 접근은 버킷 단위라 두 기능이 버킷 하나를
     * 나눠 쓰는 한 성립할 수 없었다(ssccops#156).
     */
    public SessionFileReferenceServiceImpl(
            SessionRepository sessionRepository,
            FileReferenceRepository fileReferenceRepository,
            SessionCorrectionPolicy sessionCorrectionPolicy,
            S3Presigner r2Presigner,
            @Value("${r2.bucket-name}") String bucketName,
            SessionFileReferenceViewer sessionFileReferenceViewer) {
        this.sessionRepository = sessionRepository;
        this.fileReferenceRepository = fileReferenceRepository;
        this.sessionCorrectionPolicy = sessionCorrectionPolicy;
        this.r2Presigner = r2Presigner;
        this.bucketName = bucketName;
        this.sessionFileReferenceViewer = sessionFileReferenceViewer;
    }

    /*
     * 검사 순서는 출석 정정과 같다 — 활동(404) → 소유권(403) → 회차(404) → 확정 여부(409) →
     * 형식(400). 그 넷을 SessionCorrectionPolicy 한 곳에 두는 이유는 그 클래스 주석에 있다.
     * 형식 검사를 맨 뒤에 두는 것은 남의 활동에 확장자를 바꿔 가며 부르는 것만으로 회차의
     * 존재를 알아낼 수 없게 하기 위해서다.
     */
    @Override
    @Transactional
    public FileReferenceUploadResponse issueUploadUrl(
            Long academicProgramId,
            Long sessionId,
            FileReferenceUploadRequest request,
            MemberEntity requester) {
        SessionEntity session =
                sessionCorrectionPolicy.requireCorrectable(academicProgramId, sessionId, requester);
        EventImageType imageType = resolveImageType(request);

        String objectKey =
                FileReferenceEntity.OBJECT_KEY_PREFIX
                        + "%d/sessions/%d/%s.%s"
                                .formatted(
                                        academicProgramId,
                                        sessionId,
                                        UUID.randomUUID(),
                                        imageType.getExtension());

        // 저장하는 값은 **키**다 (#200) — 읽기가 그 키로 서명한다
        FileReferenceEntity fileReference = upsert(session, objectKey);
        String uploadUrl = presignPut(objectKey, imageType);

        return new FileReferenceUploadResponse(
                fileReference.getId(),
                uploadUrl,
                sessionFileReferenceViewer.presignGet(objectKey),
                imageType.getContentType());
    }

    /*
     * 회차당 1장이므로 참조는 만들거나 갈아 끼우거나 둘 중 하나다. 지웠다 넣지 않는 것은
     * fileReferenceId가 바뀌면 화면이 들고 있던 식별자가 무효가 되기 때문이다
     * (FileReferenceEntity.changeFileUrl 주석).
     *
     * 조회 전에 회차 행을 잠근다 — 참조가 아직 없는 회차에 두 요청이 동시에 닿으면 둘 다
     * "없다"를 보고 각자 INSERT 해 늦은 쪽이 UNIQUE 위반으로 500이 된다. 잠금을 조회보다 먼저
     * 거는 순서가 요점이며(최초 가입자 부트스트랩 #71의 '잠그고 다시 센다'와 같은 두 단계),
     * 뒤집으면 잠금을 기다리는 사이 앞선 트랜잭션이 커밋해 낡은 "없다"로 통과한다.
     */
    private FileReferenceEntity upsert(SessionEntity session, String objectKey) {
        sessionRepository.lockById(session.getId());

        return fileReferenceRepository
                .findBySession(session)
                .map(
                        existing -> {
                            existing.changeFileUrl(objectKey);
                            return existing;
                        })
                .orElseGet(
                        () ->
                                fileReferenceRepository.saveAndFlush(
                                        FileReferenceEntity.of(session, objectKey)));
    }

    /*
     * contentType까지 서명에 넣으므로 웹은 **같은 Content-Type 헤더로** PUT 해야 한다. 서명에서
     * 빼면 허가받은 URL로 아무 형식이나 올릴 수 있어 확장자 검사가 무의미해진다 — 그래서 그
     * 값을 응답으로도 돌려준다(FileReferenceUploadResponse 주석).
     */
    private String presignPut(String objectKey, EventImageType imageType) {
        PutObjectRequest putObjectRequest =
                PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(objectKey)
                        .contentType(imageType.getContentType())
                        .build();

        return r2Presigner
                .presignPutObject(
                        PutObjectPresignRequest.builder()
                                .signatureDuration(UPLOAD_URL_TTL)
                                .putObjectRequest(putObjectRequest)
                                .build())
                .url()
                .toString();
    }

    /*
     * 허용 목록은 행사 이미지와 같은 EventImageType이다 — 두 벌로 두면 SVG를 뺀 이유(공개
     * 도메인에서 그대로 열려 XSS 경로가 된다) 같은 판단이 한쪽에만 반영된다. 확장자만으로
     * 찾는 것은 이 계약이 fileExt 하나만 받기 때문이며, 그 대신 contentType은 짐작하지 않고
     * 이 표의 값을 서명과 응답에 함께 쓴다.
     */
    private EventImageType resolveImageType(FileReferenceUploadRequest request) {
        return EventImageType.ofFileExtension(request.normalizedFileExt())
                .orElseThrow(
                        () ->
                                new GeneralException(
                                        AcademicProgramErrorCode.UNSUPPORTED_IMAGE_TYPE));
    }
}
