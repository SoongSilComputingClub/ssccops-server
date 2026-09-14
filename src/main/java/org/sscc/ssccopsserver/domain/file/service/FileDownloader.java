package org.sscc.ssccopsserver.domain.file.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import lombok.extern.slf4j.Slf4j;

/*
 * R2 오브젝트의 바이트를 서버로 가져오는 유일한 자리 (#400).
 *
 * ── 왜 생겼나 ────────────────────────────────────────────────
 *
 * **색인의 재료는 언제나 R2의 원본이다**(#399의 계약 — 업로드는 파싱 결과를 버린다). 파서 규칙이
 * 바뀐 뒤의 재색인이 그때의 청크가 아니라 원본에서 다시 나와야 하기 때문인데, 그러려면 요청
 * 밖에서 도는 워커가 바이트를 읽을 수 있어야 한다. 그전까지 이 도메인에는 **올리고·서명하고·
 * 지우고·복사하는** 길만 있었다.
 *
 * ── 왜 서명 URL을 내주고 워커가 HTTP로 받아 오지 않는가 ───────
 *
 * 그 길은 우리 서버가 우리 버킷에서 읽으려고 공개 주소를 한 번 만들었다가 버리는 것이고,
 * 실패가 SDK 예외가 아니라 HTTP 상태로 바뀌어 원인이 한 겹 멀어진다. `FilePresigner`가
 * «누구에게 내주는지 묻지 않는» 클래스인 것도 브라우저에 주기 위한 것이라는 전제 위에 서 있다.
 *
 * ── 바이트를 통째로 메모리에 올린다 ───────────────────────────
 *
 * `FileCopier`가 **서버 측 `CopyObject`**를 쓰는 이유(내려받아 다시 올리면 멀티파트를 두지 않은
 * #107의 근거가 복제 경로에서 되살아난다)가 여기서는 성립하지 않는다 — **파싱하려면 어차피
 * 내용을 읽어야 한다**(업로드가 요청 안에서 같은 일을 한다, #399). 대신 **쓰는 자리가 좁아야
 * 한다**: 업로드 상한 10MB와 색인 워커의 동시 실행 1건이 이 메서드가 한 번에 붙드는 양을 한
 * 벌로 끊는다. 큰 파일이 여럿 몰리는 경로에 끌어다 쓰면 #107이 그대로 되살아나고, 그런 자리는
 * 서명 URL을 내주는 쪽이 정답이다.
 *
 * **없는 키는 예외다**(`NoSuchKeyException`). 삼켜서 빈 배열을 돌려주면 «내용이 없는 문서»와
 * «원본이 사라진 문서»가 같은 모양이 되는데, 앞의 것은 파일을 고쳐 다시 올리는 일이고 뒤의
 * 것은 운영자가 버킷을 봐야 하는 일이다.
 */
@Slf4j
@Component
public class FileDownloader {

    private final S3Client r2Client;
    private final String bucketName;

    public FileDownloader(S3Client r2Client, @Value("${r2.bucket-name}") String bucketName) {
        this.r2Client = r2Client;
        this.bucketName = bucketName;
    }

    /** 오브젝트 전체를 바이트로 읽는다. 없으면 SDK 예외가 그대로 올라간다 */
    public byte[] download(String objectKey) {
        byte[] content =
                r2Client.getObjectAsBytes(
                                GetObjectRequest.builder()
                                        .bucket(bucketName)
                                        .key(objectKey)
                                        .build())
                        .asByteArray();

        log.info("R2 오브젝트를 읽었다: {} ({}바이트)", objectKey, content.length);
        return content;
    }
}
