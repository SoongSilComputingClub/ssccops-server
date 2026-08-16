package org.sscc.ssccopsserver.domain.example.service;

import org.sscc.ssccopsserver.domain.example.dto.ExampleCreateOrUpdateRequest;
import org.sscc.ssccopsserver.domain.example.dto.ExampleReadResponse;

public interface ExampleService {

    ExampleReadResponse create(ExampleCreateOrUpdateRequest req);

    ExampleReadResponse read(Long id);

    ExampleReadResponse update(Long id, ExampleCreateOrUpdateRequest req);

    void deleteSoft(Long id);
}
