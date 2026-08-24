package org.sscc.ssccopsserver.domain.event.service;

import java.util.List;

import org.sscc.ssccopsserver.domain.event.dto.EventCategoryCreateRequest;
import org.sscc.ssccopsserver.domain.event.dto.EventCategoryResponse;
import org.sscc.ssccopsserver.domain.event.dto.EventCategoryUpdateRequest;

public interface EventCategoryService {

    List<EventCategoryResponse> getCategories();

    EventCategoryResponse createCategory(EventCategoryCreateRequest request);

    EventCategoryResponse updateCategory(
            String classificationCode, EventCategoryUpdateRequest request);

    void deleteCategory(String classificationCode);
}
