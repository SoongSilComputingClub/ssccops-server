package org.sscc.ssccopsserver.domain.assistant.service;

import java.time.LocalDate;

import org.sscc.ssccopsserver.domain.assistant.code.RagApplyStatus;
import org.sscc.ssccopsserver.domain.assistant.code.RagDocumentType;
import org.sscc.ssccopsserver.domain.assistant.entity.RagDocumentEntity;

/*
 * 질의가 볼 수 있는 판본 하나 — 인용에 실릴 값만 (#403).
 *
 * **엔티티를 들고 다니지 않는다.** 이 값이 쓰이는 구간에 모델 호출이 끼어 있어 트랜잭션이 이미
 * 닫혀 있고(서비스 주석 — 커넥션을 Gemini 왕복 동안 쥐지 않는다), 준영속 엔티티의 지연 로딩
 * 필드를 그 뒤에 건드리면 응답을 만드는 자리에서 터진다. 색인 워커가 `Claimed`로 같은 일을
 * 하는 것과 같은 판단이다(#400).
 *
 * `documentCode`는 인용에 실리지 않는다 — **추천 질문(§13.3)이 «그 문서가 지금 코퍼스에
 * 있는가»를 묻는 열쇠**다. 제목으로 묻지 않는 것은 운영진이 표시명을 다듬는 순간 같은 문서가
 * 둘로 갈리기 때문이며, 그것이 `doc_cd`가 있는 이유 그대로다(#140과 같은 자리).
 *
 * `applyStatus`·`effectiveFrom`은 **화면의 «YYYY-MM-DD 시행 기준» 배지**가 쓰는 값이고
 * (§13.1), `name`·`version`은 인용 카드의 문서명·판본이다. 표시명이 색인 시점의 값(청크
 * 메타에 찍힌 것)이 아니라 **지금 값**인 것은 운영진이 화면에서 고친 이름이 답변에 곧바로
 * 반영되는 편이 맞기 때문이다 — 청크 본문의 옛 이름은 재색인이 고친다(#398).
 */
public record SearchableDocument(
        Long ragDocId,
        String documentCode,
        String name,
        Short version,
        RagDocumentType type,
        RagApplyStatus applyStatus,
        LocalDate effectiveFrom) {

    public static SearchableDocument from(RagDocumentEntity document) {
        return new SearchableDocument(
                document.getId(),
                document.getDocumentCode(),
                document.getName(),
                document.getVersion(),
                document.getType(),
                document.getApplyStatus(),
                document.getEffectiveFrom());
    }
}
