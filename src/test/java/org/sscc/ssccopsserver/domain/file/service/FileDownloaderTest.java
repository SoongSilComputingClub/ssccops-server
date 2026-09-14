package org.sscc.ssccopsserver.domain.file.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/*
 * 어느 버킷의 어느 키를 읽는가, 그리고 **없는 키가 무엇이 되는가** (#400).
 *
 * 바이트를 옮기는 일 자체는 SDK의 몫이라 확인할 것이 없다. 확인할 것은 요청에 실리는 두 값과,
 * 없는 오브젝트를 삼켜 빈 배열로 만들지 않는다는 것이다 — 삼키면 «내용이 없는 문서»와
 * «원본이 사라진 문서»가 색인 실패 사유에서 같은 모양이 된다.
 */
class FileDownloaderTest {

    private static final String BUCKET = "test-bucket";
    private static final String KEY = "rag-documents/1/original.md";

    private final S3Client r2Client = mock(S3Client.class);
    private final FileDownloader fileDownloader = new FileDownloader(r2Client, BUCKET);

    @Test
    void readsTheObjectFromTheConfiguredBucket() {
        byte[] content = "## 제1장 총칙".getBytes(StandardCharsets.UTF_8);
        when(r2Client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(
                        ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), content));

        assertThat(fileDownloader.download(KEY)).isEqualTo(content);

        ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(r2Client).getObjectAsBytes(request.capture());
        assertThat(request.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(request.getValue().key()).isEqualTo(KEY);
    }

    /** 없는 키는 예외다 — 색인 워커가 그 예외를 `fail_rsn_cn`으로 옮긴다 */
    @Test
    void doesNotSwallowAMissingObject() {
        when(r2Client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("no such key").build());

        assertThatThrownBy(() -> fileDownloader.download(KEY))
                .isInstanceOf(NoSuchKeyException.class);
    }
}
