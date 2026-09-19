package org.sscc.ssccopsserver.domain.content.service;

import java.util.List;

import org.sscc.ssccopsserver.domain.content.code.ContentCategory;
import org.sscc.ssccopsserver.domain.content.code.ContentPublishStatus;
import org.sscc.ssccopsserver.domain.content.dto.ContentPostHistoryResponse;
import org.sscc.ssccopsserver.domain.content.dto.ContentPostResponse;
import org.sscc.ssccopsserver.domain.content.dto.ContentPostSaveRequest;
import org.sscc.ssccopsserver.domain.content.dto.ContentPostSearchResponse;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/** 포스트 관리 (ssccops#381). 전부 CONTENT_MANAGE 뒤에 있다 */
public interface ContentPostService {

    ContentPostSearchResponse getPosts(
            ContentPublishStatus status, ContentCategory category, int size, String cursor);

    ContentPostResponse getPost(Long postId);

    ContentPostResponse createPost(ContentPostSaveRequest request, MemberEntity modifier);

    /** 끝난(또는 어떤) 행사의 제목·일시·장소·본문을 복사한 **초안**을 만든다 */
    ContentPostResponse createPostFromEvent(Long eventId, MemberEntity modifier);

    ContentPostResponse updatePost(
            Long postId, ContentPostSaveRequest request, MemberEntity modifier);

    ContentPostResponse publishPost(Long postId, MemberEntity modifier);

    ContentPostResponse unpublishPost(Long postId, MemberEntity modifier);

    List<ContentPostHistoryResponse> getPostHistory(Long postId);
}
