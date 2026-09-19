package org.sscc.ssccopsserver.domain.content.code;

/*
 * 콘텐츠 게시 상태 (ssccops#381 · ADR-0038). cntnt_page.pub_stts_cd · cntnt_post.pub_stts_cd에
 * 문자열로 저장된다.
 *
 * 둘뿐이다. 행사(EventStatus)의 ARCHIVED 같은 «내렸지만 보관» 상태를 두지 않은 것은 페이지·
 * 포스트에는 «끝났다»가 없기 때문이다 — 내리는 것은 곧 초안으로 돌리는 것이고, 되돌리려면
 * 다시 게시하면 된다. 삭제는 없다(이력이 법적 근거라 지우는 경로를 열지 않는다).
 *
 * 전이표는 ContentPageEntity.publish/unpublish · ContentPostEntity.publish/unpublish가 갖는다 —
 * 같은 상태에서 같은 조작을 두 번 하면 409다(CONTENT_ALREADY_PUBLISHED · CONTENT_NOT_PUBLISHED).
 */
public enum ContentPublishStatus {

    /** 작성 중 — 기본값. 익명 조회에 나오지 않는다 */
    DRAFT,

    /** 게시 — /public/v1/pages·posts에 나온다 */
    PUBLISHED
}
