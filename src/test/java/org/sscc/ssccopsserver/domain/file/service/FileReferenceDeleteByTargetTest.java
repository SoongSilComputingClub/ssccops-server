package org.sscc.ssccopsserver.domain.file.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.sscc.ssccopsserver.domain.file.code.FileTargetType;
import org.sscc.ssccopsserver.domain.file.entity.FileReferenceEntity;
import org.sscc.ssccopsserver.domain.file.repository.FileReferenceRepository;
import org.sscc.ssccopsserver.global.config.JpaAuditingConfig;
import org.sscc.ssccopsserver.global.config.JsonFormatMapperConfig;

/*
 * 다건 대상에 deleteByTarget을 부르면 무엇이 되는가 (#638) — 진짜 저장소로 본다.
 *
 * 단건 질의(findByTargetTypeAndTargetId)이던 동안 이 테스트는 IncorrectResultSizeDataAccessException
 * 으로 떨어졌다. 저장소를 목으로 두는 FileReferenceUpsertEraseTest로는 그 예외가 재현되지 않아
 * 여기 따로 둔다. 오브젝트 삭제는 커밋 뒤라(FileEraser) 여기서는 «무엇을 지우라고 했나»만 본다.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({FileReferenceService.class, JpaAuditingConfig.class, JsonFormatMapperConfig.class})
class FileReferenceDeleteByTargetTest {

    private static final Long TARGET_ID = 7L;
    private static final Long OTHER_TARGET_ID = 8L;

    @Autowired private FileReferenceService fileReferenceService;
    @Autowired private FileReferenceRepository fileReferenceRepository;

    @MockitoBean private FileEraser fileEraser;

    @Test
    void deletesEveryReferenceOfAMultiFileTargetAndLeavesOthers() {
        fileReferenceRepository.saveAll(
                List.of(
                        FileReferenceEntity.of(
                                FileTargetType.OPERATION, TARGET_ID, "operations/7/a.pdf"),
                        FileReferenceEntity.of(
                                FileTargetType.OPERATION, TARGET_ID, "operations/7/b.jpg"),
                        FileReferenceEntity.of(
                                FileTargetType.OPERATION, OTHER_TARGET_ID, "operations/8/c.zip"),
                        FileReferenceEntity.of(
                                FileTargetType.CONTENT_POST, TARGET_ID, "content-posts/7/d.png")));

        fileReferenceService.deleteByTarget(FileTargetType.OPERATION, TARGET_ID);

        assertThat(fileReferenceRepository.findAll())
                .extracting(FileReferenceEntity::objectKey)
                .containsExactlyInAnyOrder("operations/8/c.zip", "content-posts/7/d.png");
        verify(fileEraser).eraseAfterCommit(List.of("operations/7/a.pdf", "operations/7/b.jpg"));
    }
}
