package org.sscc.ssccopsserver.domain.academicprogram.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.academicprogram.code.error.AcademicProgramErrorCode;
import org.sscc.ssccopsserver.domain.academicprogram.dto.FileReferenceUploadRequest;
import org.sscc.ssccopsserver.domain.academicprogram.dto.FileReferenceUploadResponse;
import org.sscc.ssccopsserver.domain.academicprogram.entity.SessionEntity;
import org.sscc.ssccopsserver.domain.academicprogram.repository.SessionRepository;
import org.sscc.ssccopsserver.domain.file.code.FileTargetType;
import org.sscc.ssccopsserver.domain.file.code.ImageFileType;
import org.sscc.ssccopsserver.domain.file.entity.FileReferenceEntity;
import org.sscc.ssccopsserver.domain.file.service.FilePresigner;
import org.sscc.ssccopsserver.domain.file.service.FileReferenceService;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

/*
 * 출석 인증사진 업로드 URL 발급 (#137 · 학술관리_API설계.md §3.5).
 *
 * **이 서비스도 파일을 받지 않는다.** 하는 일은 "이 회차에 이 형식의 사진을 하나 올려도 좋다"는
 * 허가를 짧은 유효기간의 서명된 URL로 내주는 것뿐이고, 실제 바이트는 브라우저에서 R2로 직접
 * 간다 — 서명 자체는 파일 도메인(FilePresigner)이 만든다(#220). 멀티파트 업로드를 만들지 않는
 * 이유는 그 클래스 주석에 있다.
 *
 * **행사 이미지(#161)와 갈리는 곳은 하나다 — 여기는 DB에 행을 남긴다.** 데이터모델(§2)이
 * file_rfrnc를 요구하기 때문이고(회차 상세가 사진 유무를 그 행으로 답한다), 그래서 이 서비스는
 * readOnly가 아니다. 행이 실제 업로드보다 먼저 태어난다는 사실이 만드는 성질은
 * FileReferenceEntity 주석에 적어 두었다.
 *
 * 재업로드는 UPSERT다(설계 결정 #1) — 기존 참조를 거절하지 않고 새 키로 갈아 끼운다.
 * 그래서 클라이언트는 실패한 업로드를 같은 요청 한 번으로 다시 시도할 수 있다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SessionFileReferenceServiceImpl implements SessionFileReferenceService {

    private final SessionRepository sessionRepository;
    private final SessionCorrectionPolicy sessionCorrectionPolicy;

    /*
     * 파일 참조 행과 서명은 파일 도메인이 갖는다 (#220). 학술이 계속 갖는 것은 **누가 올릴 수
     * 있는가**(sessionCorrectionPolicy)와 **키를 어떻게 짓는가**뿐이다 — 그 둘은 이 도메인의
     * 규칙이라 공통으로 올리면 대상 구분별 분기표가 된다.
     */
    private final FileReferenceService fileReferenceService;
    private final FilePresigner filePresigner;

    /*
     * 미리보기 주소를 만드는 자리 (#200). 읽기 서명은 회차 상세와 한 곳에서만 만든다 — 자격
     * 판정이 그쪽에 있고, 발급이 곧 읽기 권한이라 두 경로가 다른 판정을 쓰면 안 된다.
     */
    private final SessionFileReferenceViewer sessionFileReferenceViewer;

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
        ImageFileType imageType = resolveImageType(request);

        /*
         * 크기 안내 (ssccops#188). 형식 검사 뒤에 두는 것은 위 주석의 순서 규칙을 그대로
         * 따르는 것이고 — 남의 활동에 값을 바꿔 가며 불러 회차의 존재를 알아낼 수 없어야 한다 —
         * 상한을 여기 상수로 두지 않는 것은 **강제하는 값과 안내하는 값이 갈리지 않게** 하기
         * 위해서다(그 값은 서명하는 FilePresigner가 갖는다).
         */
        if (request.fileSize() > filePresigner.maxUploadSizeBytes()) {
            throw new GeneralException(AcademicProgramErrorCode.IMAGE_TOO_LARGE);
        }

        String objectKey =
                FileTargetType.SESSION.getObjectKeyPrefix()
                        + "%d/sessions/%d/%s.%s"
                                .formatted(
                                        academicProgramId,
                                        sessionId,
                                        UUID.randomUUID(),
                                        imageType.getExtension());

        // 저장하는 값은 **키**다 (#200) — 읽기가 그 키로 서명한다
        FileReferenceEntity fileReference = upsert(session, objectKey);
        // 서명에 넘기는 크기는 위 413이 본 값 그대로다 — 안내와 강제가 같은 숫자를 봐야 한다
        String uploadUrl =
                filePresigner.presignPut(objectKey, imageType.getContentType(), request.fileSize());

        return new FileReferenceUploadResponse(
                fileReference.getId(),
                uploadUrl,
                sessionFileReferenceViewer.presignGet(objectKey),
                imageType.getContentType());
    }

    /*
     * 회차당 1장이므로 참조는 만들거나 갈아 끼우거나 둘 중 하나다(UPSERT 자체는
     * FileReferenceService가 한다).
     *
     * **조회 전에 회차 행을 잠그는 것이 이 메서드가 남아 있는 이유다.** 참조가 아직 없는
     * 회차에 두 요청이 동시에 닿으면 둘 다 "없다"를 보고 각자 INSERT 해 늦은 쪽이 UNIQUE
     * 위반으로 500이 된다 — 그 UNIQUE가 PostgreSQL 부분 인덱스(SESSION 한정)라 H2에는 없으므로
     * 이 잠금이 유일한 방어선인 환경도 있다. 잠금을 조회보다 먼저 거는 순서가 요점이며(최초
     * 가입자 부트스트랩 #71의 '잠그고 다시 센다'와 같은 두 단계), 뒤집으면 잠금을 기다리는
     * 사이 앞선 트랜잭션이 커밋해 낡은 "없다"로 통과한다.
     *
     * 잠그는 대상이 file_rfrnc가 아니라 sesn인 것은 참조 행이 아직 없을 수 있어서다(잠글 행이
     * 없으면 아무것도 막지 못한다). 그래서 파일 도메인은 이 잠금을 대신해 줄 수 없다.
     */
    private FileReferenceEntity upsert(SessionEntity session, String objectKey) {
        sessionRepository.lockById(session.getId());
        return fileReferenceService.upsert(FileTargetType.SESSION, session.getId(), objectKey);
    }

    /*
     * 허용 목록은 행사 이미지와 같은 ImageFileType이다 — 두 벌로 두면 SVG를 뺀 이유(브라우저가
     * 그대로 열어 XSS 경로가 된다) 같은 판단이 한쪽에만 반영된다. 확장자만으로 찾는 것은 이
     * 계약이 fileExt 하나만 받기 때문이며, 그 대신 contentType은 짐작하지 않고 이 표의 값을
     * 서명과 응답에 함께 쓴다.
     */
    private ImageFileType resolveImageType(FileReferenceUploadRequest request) {
        return ImageFileType.ofFileExtension(request.normalizedFileExt())
                .orElseThrow(
                        () ->
                                new GeneralException(
                                        AcademicProgramErrorCode.UNSUPPORTED_IMAGE_TYPE));
    }
}
