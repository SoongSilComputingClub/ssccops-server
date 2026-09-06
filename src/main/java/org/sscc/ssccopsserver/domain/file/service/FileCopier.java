package org.sscc.ssccopsserver.domain.file.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import lombok.extern.slf4j.Slf4j;

/*
 * R2 오브젝트를 다른 키로 복사하는 유일한 자리 (ssccops#198 · 행사 복제 결정 2).
 *
 * **서버 측 복사(CopyObject)다.** 바이트가 서버를 거치지 않는다 — 내려받아 다시 올리면
 * 멀티파트 업로드를 두지 않은 이유(#107 · 512MB 컨테이너)가 복제 경로에서 그대로 되살아난다.
 *
 * **지우는 쪽(FileEraser)과 시점이 반대다.** 그쪽은 커밋 뒤에 지운다 — 되돌릴 수 없는 일이라
 * 롤백된 변경 뒤에 "DB는 옛 상태인데 파일만 없는" 조합을 남기지 않기 위해서다. 복사는
 * 트랜잭션 **안에서** 한다: 실패하면 예외가 그대로 올라가 행사 사본까지 함께 롤백되고,
 * 성공한 뒤 DB가 롤백되면 남는 것은 아무도 참조하지 않는 오브젝트(비용)뿐이다. 두 규칙이
 * 같은 방향을 본다 — **잘못된 데이터보다 고아가 낫다.** 복사를 커밋 뒤로 미루면 실패했을 때
 * 사본의 본문이 없는 오브젝트를 가리킨 채 커밋된다.
 *
 * **원본이 없으면 실패가 아니다.** 서버는 PUT을 관측하지 않아(EventImageServiceImpl 주석)
 * 본문의 참조가 실물을 가리킨다는 보장이 애초에 없다 — 원본 행사에서 이미 깨진 이미지가
 * 사본 때문에 복제 자체를 막으면 운영자는 그 이미지를 본문에서 찾아 지우기 전까지 아무것도
 * 못 한다. 없는 원본은 건너뛰고 false를 돌려주며, 그 참조는 원본에서와 같이 사본에서도 깨져
 * 있을 뿐 잃는 것이 없다.
 */
@Slf4j
@Component
public class FileCopier {

    private final S3Client r2Client;
    private final String bucketName;

    public FileCopier(S3Client r2Client, @Value("${r2.bucket-name}") String bucketName) {
        this.r2Client = r2Client;
        this.bucketName = bucketName;
    }

    /** 같은 버킷 안에서 복사한다. 원본이 없으면 false, 그 밖의 실패는 SDK 예외가 그대로 올라간다 — 부르는 쪽이 도메인 오류로 옮긴다. */
    public boolean copy(String sourceKey, String destinationKey) {
        try {
            r2Client.copyObject(
                    CopyObjectRequest.builder()
                            .sourceBucket(bucketName)
                            .sourceKey(sourceKey)
                            .destinationBucket(bucketName)
                            .destinationKey(destinationKey)
                            .build());
            log.info("R2 오브젝트를 복사했다: {} → {}", sourceKey, destinationKey);
            return true;
        } catch (NoSuchKeyException e) {
            log.warn("R2 원본 오브젝트가 없어 복사를 건너뛴다: {}", sourceKey);
            return false;
        }
    }
}
