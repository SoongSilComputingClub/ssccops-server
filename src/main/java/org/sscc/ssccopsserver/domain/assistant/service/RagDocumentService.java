package org.sscc.ssccopsserver.domain.assistant.service;

import org.springframework.web.multipart.MultipartFile;
import org.sscc.ssccopsserver.domain.assistant.dto.RagDocumentUploadResponse;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/** 규정 도우미 코퍼스를 바꾸는 조작 (#399 · 기획안 §10). 질의(#403)는 인가 요구가 달라 다른 서비스·다른 컨트롤러다. */
public interface RagDocumentService {

    /**
     * 새 판본을 올린다 — <b>파싱은 이 요청 안에서 하고 임베딩은 부르지 않는다</b>(#399).
     *
     * @param documentCode 판본을 가로지르는 열쇠. 같은 값으로 다시 올리면 직전 판본 + 1이 된다
     * @param name 표시명. 비우면 파일명에서 확장자를 뗀 것이 들어간다
     * @param registrant 올린 회원. <b>요청 본문이 아니라 {@code @CurrentMember}에서 온다</b>(#78)
     */
    RagDocumentUploadResponse upload(
            MultipartFile file, String documentCode, String name, MemberEntity registrant);
}
