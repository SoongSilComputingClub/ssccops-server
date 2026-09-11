package org.sscc.ssccopsserver.domain.file.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

/*
 * 지우는 시점과 실패 처리 (ssccops#188).
 *
 * 확인하려는 것은 "지운다"가 아니라 **언제 지우고, 실패하면 무엇이 되는가**다 — 그 둘이
 * 이 클래스가 존재하는 이유이고, 잘못되면 되돌릴 수 없는 쪽으로 잘못된다.
 */
class FileEraserTest {

    private static final String BUCKET = "test-bucket";
    private static final String KEY = "events/1/poster.png";

    private final S3Client r2Client = mock(S3Client.class);
    private final FileEraser fileEraser = new FileEraser(r2Client, BUCKET);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** 트랜잭션이 없으면 되돌아갈 것이 없으므로 즉시 지운다 */
    @Test
    void erasesImmediatelyWithoutTransaction() {
        fileEraser.eraseAfterCommit(KEY);

        verify(r2Client).deleteObject(any(DeleteObjectRequest.class));
    }

    /*
     * **트랜잭션 안에서는 커밋 전까지 지우지 않는다.** 안에서 지우면 그 뒤 롤백된 변경 때문에
     * "DB는 옛 상태인데 파일만 없는" 조합이 남는다 — 파일 삭제에는 롤백이 없다.
     */
    @Test
    void doesNotEraseBeforeCommit() {
        TransactionSynchronizationManager.initSynchronization();

        fileEraser.eraseAfterCommit(KEY);

        verify(r2Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    /** 커밋되면 그때 지운다 */
    @Test
    void erasesAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        fileEraser.eraseAfterCommit(List.of(KEY, "events/1/other.png"));

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization -> synchronization.afterCommit());

        verify(r2Client, times(2)).deleteObject(any(DeleteObjectRequest.class));
    }

    /*
     * **삭제 실패가 밖으로 나가지 않는다.** DB가 정본이고 정리는 뒤따르는 일이라, 고아 하나
     * 때문에 이미 커밋된 사진 교체나 행사 저장이 실패로 보이면 사용자가 할 수 있는 일이 없다.
     * 커밋 뒤에 던지면 그 예외가 갈 곳도 없다.
     */
    @Test
    void swallowsDeleteFailure() {
        doThrow(SdkException.builder().message("boom").build())
                .when(r2Client)
                .deleteObject(any(DeleteObjectRequest.class));

        assertThatCode(() -> fileEraser.eraseAfterCommit(KEY)).doesNotThrowAnyException();
    }

    /** 빈 입력은 아무 일도 하지 않는다 — 부르는 쪽이 매번 확인하지 않아도 되게 */
    @Test
    void ignoresEmptyInput() {
        fileEraser.eraseAfterCommit((String) null);
        fileEraser.eraseAfterCommit("  ");
        fileEraser.eraseAfterCommit(List.of());

        verify(r2Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }
}
