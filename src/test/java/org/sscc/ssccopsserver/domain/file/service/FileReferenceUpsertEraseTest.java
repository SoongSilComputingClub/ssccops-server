package org.sscc.ssccopsserver.domain.file.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.sscc.ssccopsserver.domain.file.code.FileTargetType;
import org.sscc.ssccopsserver.domain.file.entity.FileReferenceEntity;
import org.sscc.ssccopsserver.domain.file.repository.FileReferenceRepository;

/*
 * 참조를 갈아 끼울 때(ssccops#188)와 대상이 사라질 때(#401) 오브젝트를 지우는 규칙.
 *
 * 통합 테스트로는 확인할 수 없는 자리다 — 삭제가 커밋 뒤에 일어나는데 통합 테스트는
 * @Transactional이라 그 시점이 오지 않는다. 그래서 "무엇을 지우라고 했는가"를 여기서 본다.
 */
class FileReferenceUpsertEraseTest {

    private static final Long TARGET_ID = 7L;
    private static final String OLD_KEY = "academic-programs/1/sessions/7/old.jpg";
    private static final String NEW_KEY = "academic-programs/1/sessions/7/new.jpg";

    private final FileReferenceRepository repository = mock(FileReferenceRepository.class);
    private final FileEraser fileEraser = mock(FileEraser.class);
    private final FileReferenceService service = new FileReferenceService(repository, fileEraser);

    /*
     * **키가 바뀌면 옛 것을 지운다.** 그 순간 옛 오브젝트는 이미 앱에서 닿을 수 없다 —
     * 이 행이 유일한 참조이고 방금 새 키를 가리켰다. 발급만 받고 업로드를 포기해도 같다:
     * 옛 사진은 어차피 보이지 않으므로 지우는 것이 잃는 것이 없다.
     */
    @Test
    void erasesPreviousObjectWhenKeyChanges() {
        FileReferenceEntity existing =
                FileReferenceEntity.of(FileTargetType.SESSION, TARGET_ID, OLD_KEY);
        when(repository.findByTargetTypeAndTargetId(FileTargetType.SESSION, TARGET_ID))
                .thenReturn(Optional.of(existing));

        service.upsert(FileTargetType.SESSION, TARGET_ID, NEW_KEY);

        verify(fileEraser).eraseAfterCommit(OLD_KEY);
    }

    /*
     * **같은 키로 다시 저장하면 지우지 않는다.** 지우면 방금 올린 것을 지우는 셈이다 —
     * 이 분기가 없으면 재시도 한 번이 사진을 없앤다.
     */
    @Test
    void doesNotEraseWhenKeyIsUnchanged() {
        FileReferenceEntity existing =
                FileReferenceEntity.of(FileTargetType.SESSION, TARGET_ID, OLD_KEY);
        when(repository.findByTargetTypeAndTargetId(FileTargetType.SESSION, TARGET_ID))
                .thenReturn(Optional.of(existing));

        service.upsert(FileTargetType.SESSION, TARGET_ID, OLD_KEY);

        verify(fileEraser, never()).eraseAfterCommit(any(String.class));
    }

    /** 참조가 처음 생기는 경우에는 지울 옛 것이 없다 */
    @Test
    void doesNotEraseOnFirstUpload() {
        when(repository.findByTargetTypeAndTargetId(FileTargetType.SESSION, TARGET_ID))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(FileReferenceEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.upsert(FileTargetType.SESSION, TARGET_ID, NEW_KEY);

        verify(fileEraser, never()).eraseAfterCommit(any(String.class));
        verify(repository).saveAndFlush(any(FileReferenceEntity.class));
    }

    /*
     * **대상이 사라지면 참조와 오브젝트를 함께 지운다** (#401 · 규정 문서 하드 삭제).
     *
     * 대상 도메인이 각자 지우게 하지 않은 이유는 `upsert`와 같다 — 키를 아는 자리가 여기 하나뿐이고,
     * 한 곳을 잊으면 «행은 없는데 버킷에 있는» 오브젝트가 남아 아무도 찾지 못한다.
     */
    @Test
    void deletesReferenceAndErasesObject() {
        FileReferenceEntity existing =
                FileReferenceEntity.of(FileTargetType.RAG_DOCUMENT, TARGET_ID, OLD_KEY);
        when(repository.findByTargetTypeAndTargetId(FileTargetType.RAG_DOCUMENT, TARGET_ID))
                .thenReturn(Optional.of(existing));

        service.deleteByTarget(FileTargetType.RAG_DOCUMENT, TARGET_ID);

        verify(repository).delete(existing);
        verify(fileEraser).eraseAfterCommit(OLD_KEY);
    }

    /*
     * 참조가 없으면 아무 일도 하지 않는다 — 업로드가 중간에 실패해 행만 있는 대상이 정상적으로
     * 있을 수 있고, 그것은 «지울 것이 없다»는 뜻이지 오류가 아니다.
     */
    @Test
    void deleteIsQuietWhenThereIsNoReference() {
        when(repository.findByTargetTypeAndTargetId(FileTargetType.RAG_DOCUMENT, TARGET_ID))
                .thenReturn(Optional.empty());

        service.deleteByTarget(FileTargetType.RAG_DOCUMENT, TARGET_ID);

        verify(repository, never()).delete(any(FileReferenceEntity.class));
        verify(fileEraser, never()).eraseAfterCommit(any(String.class));
    }

    /** 부르지 않은 것도 못 박아 둔다 — 조회 키가 어긋나면 위 단언이 통과해도 실제로는 안 지운다 */
    @Test
    void looksUpByTheGivenTarget() {
        when(repository.findByTargetTypeAndTargetId(any(), anyLong())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(FileReferenceEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.upsert(FileTargetType.SESSION, TARGET_ID, NEW_KEY);

        verify(repository).findByTargetTypeAndTargetId(FileTargetType.SESSION, TARGET_ID);
    }
}
