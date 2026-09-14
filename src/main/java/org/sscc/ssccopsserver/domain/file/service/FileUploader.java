package org.sscc.ssccopsserver.domain.file.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import lombok.extern.slf4j.Slf4j;

/*
 * 서버가 가진 바이트를 R2에 올리는 유일한 자리 (#399 · 기획안 §9).
 *
 * ── 서버가 바이트를 만지는 것이 예외다 ─────────────────────────
 *
 * 이 도메인의 다른 업로드 경로는 전부 presigned PUT이다(FilePresigner) — 서버가 버퍼링하면
 * 동시 업로드가 몰릴 때 메모리가 요청 수에 비례해 늘고, 512MB 컨테이너에서 실제로 그 일이
 * 있었다(#107). **규정 문서에서는 그 근거가 성립하지 않는다: 파싱하려면 어차피 내용을 읽어야
 * 한다.** 요청 안에서 파싱하고 그 자리에서 400을 돌려주는 것이 업로드의 계약이므로(#399),
 * 바이트는 이미 힙에 있고 그것을 다시 브라우저에게 올리게 할 이유가 없다. 대신 동시성은
 * 파일 10MB 상한과 적재 레이트 리밋(회원당 일 10회)이 좁힌다.
 *
 * **그래서 이 클래스를 쓰는 자리는 좁아야 한다.** 이미지처럼 큰 파일이 여럿 몰리는 경로에
 * 끌어다 쓰면 #107이 그대로 되살아난다 — 그런 자리는 presigned PUT이 정답이다.
 *
 * ── 트랜잭션 안에서 올린다 (FileCopier와 같은 쪽) ────────────────
 *
 * 지우는 쪽(FileEraser)은 커밋 뒤에 지우고 이쪽은 트랜잭션 안에서 올린다. 방향이 반대로
 * 보이지만 같은 규칙이다 — **잘못된 데이터보다 고아가 낫다.** 실패하면 예외가 그대로 올라가
 * 행까지 함께 롤백되고, 성공한 뒤 DB가 롤백되면 남는 것은 아무도 참조하지 않는 오브젝트(비용)
 * 뿐이다. 커밋 뒤로 미루면 실패했을 때 «DB에는 문서가 있는데 원본이 없는» 행이 남는데, 재색인의
 * 재료가 그 원본이라 그 행은 영영 색인될 수 없다.
 */
@Slf4j
@Component
public class FileUploader {

    private final S3Client r2Client;
    private final String bucketName;

    public FileUploader(S3Client r2Client, @Value("${r2.bucket-name}") String bucketName) {
        this.r2Client = r2Client;
        this.bucketName = bucketName;
    }

    /**
     * 바이트를 그 키에 올린다. 실패는 SDK 예외가 그대로 올라간다 — 부르는 쪽이 도메인 오류로 옮기거나, 그대로 두어 트랜잭션을 되돌린다.
     *
     * <p><b>키를 조립하지 않는다.</b> 접두사는 대상이 갖고({@code FileTargetType}) 그 뒤의 모양은 대상 도메인의 규칙이라, 여기서 만들면 같은
     * 규칙이 두 벌이 된다.
     *
     * <p><b>{@code contentType}은 요청이 신고한 값이 아니라 형식 표에서 온 값을 넘긴다</b> — 브라우저가 보내는 MIME은 파일의 정체가 아니라
     * 신고이고, 그 값이 그대로 오브젝트에 굳으면 나중에 내려받을 때 형식이 어긋난다.
     */
    public void upload(String objectKey, byte[] content, String contentType) {
        r2Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(objectKey)
                        .contentType(contentType)
                        .contentLength((long) content.length)
                        .build(),
                RequestBody.fromBytes(content));
        log.info("R2 오브젝트를 올렸다: {} ({}바이트)", objectKey, content.length);
    }
}
