package org.sscc.ssccopsserver.domain.event.service;

import org.sscc.ssccopsserver.domain.event.dto.EventImageUploadRequest;
import org.sscc.ssccopsserver.domain.event.dto.EventImageUploadResponse;

public interface EventImageService {

    EventImageUploadResponse issueUploadUrl(Long eventId, EventImageUploadRequest request);
}
