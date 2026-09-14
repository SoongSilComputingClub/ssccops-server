package org.sscc.ssccopsserver.domain.assistant.service;

import org.springframework.web.multipart.MultipartFile;
import org.sscc.ssccopsserver.domain.assistant.dto.RagDocumentApplyStatusUpdateRequest;
import org.sscc.ssccopsserver.domain.assistant.dto.RagDocumentDetailResponse;
import org.sscc.ssccopsserver.domain.assistant.dto.RagDocumentListResponse;
import org.sscc.ssccopsserver.domain.assistant.dto.RagDocumentResponse;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/** 규정 도우미 코퍼스를 바꾸는 조작 (#399 · #401 · 기획안 §10). 질의(#403)는 인가 요구가 달라 다른 서비스·다른 컨트롤러다. */
public interface RagDocumentService {

    /**
     * 새 판본을 올린다 — <b>파싱은 이 요청 안에서 하고 임베딩은 부르지 않는다</b>(#399).
     *
     * @param documentCode 판본을 가로지르는 열쇠. 같은 값으로 다시 올리면 직전 판본 + 1이 된다
     * @param name 표시명. 비우면 파일명에서 확장자를 뗀 것이 들어간다
     * @param registrant 올린 회원. <b>요청 본문이 아니라 {@code @CurrentMember}에서 온다</b>(#78)
     */
    RagDocumentResponse upload(
            MultipartFile file, String documentCode, String name, MemberEntity registrant);

    /**
     * 목록과 <b>요약 3값을 한 응답으로</b> (#401).
     *
     * @param keyword 문서명 부분 일치(요청 파라미터 이름은 {@code q}다). 비면 전량이며, <b>있어도 요약은 코퍼스 전체다</b> — 이유는
     *     {@code RagCorpusSummaryResponse}에 있다
     */
    RagDocumentListResponse list(String keyword);

    /** 상세 — 조 목록({@code STRUCTURED}) · 실패 사유 · 원본 다운로드 URL (#401) */
    RagDocumentDetailResponse detail(Long ragDocId);

    /**
     * 적용 상태를 바꾼다 — {@code DRAFT → EFFECTIVE} · {@code EFFECTIVE → SUPERSEDED} (#401 · 기획안 §5.5).
     *
     * <p><b>{@code EFFECTIVE}로 올리면 같은 {@code doc_cd}의 기존 시행본이 같은 트랜잭션에서 내려간다</b>(대표 역할 {@code
     * rprs_role_yn}이 회원당 1건인 것과 같은 모양). 성립하지 않는 전이는 엔티티의 전이표가 거절한다.
     */
    RagDocumentResponse changeApplyStatus(
            Long ragDocId, RagDocumentApplyStatusUpdateRequest request);

    /**
     * 재색인 — <b>{@code PENDING}으로 다시 줄을 세우는 것뿐이다</b>(#400). 워커가 그 상태만 집으므로 전용 경로를 만들면 색인 로직이 두 벌이 된다
     */
    RagDocumentResponse reindex(Long ragDocId);

    /** 하드 삭제 — 행 · 청크 · R2 오브젝트 (#401 · ADR-0029). <b>되살리기가 없다</b> — 되돌리려면 같은 파일을 새 판본으로 다시 올린다 */
    void delete(Long ragDocId);
}
