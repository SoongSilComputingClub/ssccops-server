package org.sscc.ssccopsserver.domain.file.service;

import java.util.Collection;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

import lombok.extern.slf4j.Slf4j;

/*
 * R2 오브젝트를 지우는 유일한 자리 (ssccops#188 · ADR-0014).
 *
 * **여기가 생기기 전까지 지우는 코드가 한 줄도 없었다.** R2Config가 S3Client 빈을 만들어
 * 두었지만 아무도 주입받지 않았다(FilePresigner는 S3Presigner만 쓴다) — 그 빈이 놀고 있다는
 * 것이 곧 지우는 경로가 없다는 증거였다. 이 클래스가 그 빈의 첫 사용처다.
 *
 * **커밋 뒤에 지운다.** 지우는 일은 되돌릴 수 없는데 그것을 부르는 자리는 전부 트랜잭션
 * 안이라, 안에서 지우면 그 뒤 롤백된 변경 때문에 "DB는 옛 상태인데 파일만 없는" 조합이 남는다.
 * 트랜잭션이 없으면 그냥 지운다 — 되돌아갈 것이 없다.
 *
 * **실패해도 본래 작업을 되돌리지 않는다.** DB가 정본이고 오브젝트 정리는 뒤따르는 일이다 —
 * 고아 하나 때문에 사진 교체나 행사 저장이 실패하면 사용자가 할 수 있는 일이 없다. 실패는
 * 로그로 남기며, 남은 오브젝트는 비용이 늘 뿐 잘못된 데이터를 만들지 않는다(반대로 지우지
 * 말아야 할 것을 지우는 쪽은 되돌릴 수 없다 — 그래서 위 두 규칙이 이 방향으로 서 있다).
 *
 * **접두사 일괄 삭제를 두지 않는다.** 그것이 필요했던 자리는 행사 하드 삭제 하나였는데
 * ADR-0014로 삭제 API 자체가 없어졌다 — 보관은 되돌릴 수 있어야 하므로(REPUBLISH) 그 시점에
 * 객체를 지우면 재공개한 행사의 이미지가 통째로 깨진다. 지금 지우는 것은 언제나 **키를 정확히
 * 아는 오브젝트**뿐이다.
 */
@Slf4j
@Component
public class FileEraser {

    private final S3Client r2Client;
    private final String bucketName;

    public FileEraser(S3Client r2Client, @Value("${r2.bucket-name}") String bucketName) {
        this.r2Client = r2Client;
        this.bucketName = bucketName;
    }

    /** 커밋된 뒤에 지운다. 트랜잭션 밖에서 부르면 즉시 지운다 */
    public void eraseAfterCommit(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        eraseAfterCommit(List.of(objectKey));
    }

    /*
     * 여러 키를 한 번에. **배치 삭제(DeleteObjects) API를 쓰지 않고 건별로 지운다** — 지우는
     * 대상이 본문 편집 한 번에 빠진 이미지 몇 장 수준이라 왕복을 줄여 얻는 것이 없고, 배치는
     * 부분 실패를 응답 본문으로 돌려주므로 "어느 것이 남았는가"를 따로 읽어야 한다. 건별이면
     * 실패한 것만 로그에 남고 나머지는 그대로 지워진다.
     */
    public void eraseAfterCommit(Collection<String> objectKeys) {
        if (objectKeys == null || objectKeys.isEmpty()) {
            return;
        }
        List<String> keys = List.copyOf(objectKeys);

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            keys.forEach(this::erase);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        keys.forEach(FileEraser.this::erase);
                    }
                });
    }

    private void erase(String objectKey) {
        try {
            r2Client.deleteObject(
                    DeleteObjectRequest.builder().bucket(bucketName).key(objectKey).build());
            log.info("R2 오브젝트를 지웠다: {}", objectKey);
        } catch (RuntimeException e) {
            /*
             * 삼키는 것이 의도다(위 클래스 주석). 남은 오브젝트는 비용이지 결함이 아니므로,
             * 이 실패로 이미 커밋된 변경을 되돌릴 방법도 없고 되돌려서도 안 된다.
             */
            log.error("R2 오브젝트 삭제 실패 — 고아로 남는다: {}", objectKey, e);
        }
    }
}
