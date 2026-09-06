package org.sscc.ssccopsserver.domain.share.service;

import java.util.Optional;

import org.sscc.ssccopsserver.domain.share.code.ShareTargetType;

/*
 * 대상 종류별 미리보기 제공자 (ssccops#200).
 *
 * **공유 도메인은 대상이 무엇인지 모른다.** 하위 업무의 제목이 어느 컬럼인지, 무엇을 요약으로
 * 쓸지는 그 도메인이 알아야 하고, 공유 도메인에 대상별 분기표를 만들면 대상이 하나 늘 때마다
 * 이 패키지에 남의 도메인 이름이 하나씩 박힌다 — `SystemFormApprovalHook`이 폼 도메인에서
 * 학술 도메인을 모른 채 승인 후속 처리를 부르는 것과 같은 구조다.
 *
 * 구현체는 **그 대상을 소유한 도메인**에 둔다(하위 업무 → `domain/operation`).
 */
public interface SharePreviewProvider {

    /** 이 제공자가 맡는 대상 종류 */
    ShareTargetType targetType();

    /*
     * 대상 한 건의 미리보기. 대상이 없거나 지워졌으면 빈 Optional이며, 그때 응답은 폐기된
     * 링크와 같은 404다.
     *
     * **인가를 묻지 않는다** — 이 경로는 정의상 익명이고, 토큰이 미리보기 권한 그 자체다
     * (ADR-0016). 대신 무엇을 실을지를 좁히는 것이 구현체의 책임이다.
     */
    Optional<SharePreview> preview(Long targetId);
}
