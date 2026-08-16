package org.sscc.ssccopsserver.domain.example.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.example.code.ExampleErrorCode;
import org.sscc.ssccopsserver.domain.example.dto.ExampleCreateOrUpdateRequest;
import org.sscc.ssccopsserver.domain.example.dto.ExampleReadResponse;
import org.sscc.ssccopsserver.domain.example.entity.ExampleEntity;
import org.sscc.ssccopsserver.domain.example.entity.ExampleStatus;
import org.sscc.ssccopsserver.domain.example.repository.ExampleRepository;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ExampleServiceImpl implements ExampleService {

    private final ExampleRepository exampleRepository;

    @Transactional
    public ExampleReadResponse create(ExampleCreateOrUpdateRequest req) {
        ExampleEntity saved =
                exampleRepository.save(ExampleEntity.create(req.title(), req.content()));
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public ExampleReadResponse read(Long id) {
        return toResponse(findActive(id));
    }

    @Transactional
    public ExampleReadResponse update(Long id, ExampleCreateOrUpdateRequest req) {
        ExampleEntity entity = findActive(id);
        entity.update(req.title(), req.content());
        return toResponse(entity);
    }

    // 소프트 삭제
    @Transactional
    public void deleteSoft(Long id) {
        findActive(id).softDelete();
    }

    // ------------------ private ------------------

    // 소프트 삭제된 예시는 read/update/delete에서 접근 불가
    private ExampleEntity findActive(Long id) {
        return exampleRepository
                .findByIdAndStatusNot(id, ExampleStatus.DELETED)
                .orElseThrow(() -> new GeneralException(ExampleErrorCode.EXAMPLE_NOT_FOUND));
    }

    private ExampleReadResponse toResponse(ExampleEntity entity) {
        return new ExampleReadResponse(
                entity.getId(), entity.getTitle(), entity.getContent(), entity.getStatus());
    }
}
