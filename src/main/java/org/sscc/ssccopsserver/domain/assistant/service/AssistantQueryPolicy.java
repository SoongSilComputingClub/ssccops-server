package org.sscc.ssccopsserver.domain.assistant.service;

import java.util.EnumMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.assistant.code.RagDocumentType;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/*
 * 검색·거절의 손잡이 (#403 · 기획안 §6.1 · §8.1).
 *
 * ══ 임계값이 문서 유형별인 이유 ═════════════════════════════════
 *
 * **조 단위 청크(목표 450자)와 고정 길이 청크(600자 + overlap)는 점수 분포가 같지 않다.**
 * 앞의 것은 한 조가 통째로 들어가 질문과 어휘가 맞아떨어지고, 뒤의 것은 문단 중간에서 잘린
 * 조각이라 같은 관련도에서도 점수가 낮게 나온다. 임계값을 하나로 두면 한쪽이 언제나 새거나
 * (거절이 안 되거나) 한쪽이 언제나 막힌다.
 *
 * ⚠️ **골든셋의 방향 결론은 틀렸다 — 실제 임베딩에서는 반대다** (#457). `RetrievalGoldenSetTest`의
 * 스텁은 문자 n-gram 코사인이라 어휘가 집중된 짧은 조 청크에 유리하고(그래서 조 단위가 고정
 * 길이보다 «언제나 높게» 나왔다 · 비 1.07~2.98), 밀집 임베딩은 그 반대다. 2026-09-17 실측:
 *
 * <pre>
 *   표결 방식으로 무엇을 제안했어?   GENERIC      정답 1위 (0.6241)
 *   회칙 제3조                     STRUCTURED   정답 8~9위 (0.5548~0.6490)
 * </pre>
 *
 * 조 단위 청크는 평균 180자 · 최소 38자라 661자짜리 평문 청크와 같은 공간에서 경쟁하면 밀린다.
 * **유형별 손잡이가 필요하다는 것은 그대로이고, 움직일 방향만 뒤집혔다.**
 *
 * **두 기본값은 여전히 같고, 그것은 유형별로 «아직 가르지 않았다»는 표시다.** 다만 공통값 자체는
 * 이제 실측에서 나왔다 — 0.5에서 **0.35**로 내렸다(#457 · 근거는 `application.yaml` 주석).
 *
 * ⚠️ **절대값으로 «관련 있음»을 가를 눈금은 없다.** 정당한 질문(「제21조에 대해 알려줘」 1위
 * 0.4791)이 무관한 질문(「파이썬에서 리스트를 정렬하는 방법」 1위 0.4910)보다 낮게 나온다.
 * **그래서 거절의 주체는 이 손잡이가 아니라 모델의 `[근거없음]`(#455)이고**, 이 값은 그 뒤에
 * 남은 최후의 안전장치다 — 무관한 질문에 발췌 5개를 실어 보내도 모델이 거절하는 것을 확인했다.
 *
 * 유형별로 가를 값이 필요하면 **`./gradlew geminiCheck` 의 7단계**에서 재며, 고칠 때 손대는 것은
 * 코드가 아니라 배포 환경변수 한 줄이다. 그 손잡이를 **미리 열어 두는 것**이 이 클래스가 하는
 * 일의 전부다.
 *
 * ══ 요청이 이 값들을 고르지 못한다 ══════════════════════════════
 *
 * `topK`도 임계값도 요청 파라미터가 아니다. 열면 «임계값 0으로 물어보기»가 가능해지고, 그것은
 * 거절(§6.1)을 **클라이언트가 끌 수 있다**는 뜻이다 — 이 기능에서 가장 중요한 동작이 그것이다.
 */
@Slf4j
@Getter
@Component
public class AssistantQueryPolicy {

    /*
     * 한 번에 넣어 주는 발췌 수. 8은 기획안이 적은 값이며(§4) 답변 3~5문장(§6.2)과 함께
     * 프롬프트 길이를 정한다 — 늘리면 힙과 모델 입력이 함께 는다(§8.1).
     */
    private final int topK;

    /** 유형별 임계값이 없을 때 쓰는 값 */
    private final double similarityThreshold;

    private final Map<RagDocumentType, Double> thresholdByType =
            new EnumMap<>(RagDocumentType.class);

    /** 질문 길이 상한 — <b>품질 규칙이 아니라 용량 규칙이다</b>(§8.1). 넘으면 413 {@code ASSISTANT_QUESTION_TOO_LONG}. */
    private final int maxQuestionLength;

    /** 인용 카드에 싣는 원문 발췌 길이. 조문을 통째로 옮기지 않는다(§6.2 다섯째 규칙과 같은 줄기) */
    private final int snippetLength;

    public AssistantQueryPolicy(
            @Value("${ssccops.assistant.query.top-k}") int topK,
            @Value("${ssccops.assistant.query.similarity-threshold}") double similarityThreshold,
            @Value("${ssccops.assistant.query.similarity-threshold-structured:#{null}}")
                    Double structuredThreshold,
            @Value("${ssccops.assistant.query.similarity-threshold-generic:#{null}}")
                    Double genericThreshold,
            @Value("${ssccops.assistant.query.max-question-length}") int maxQuestionLength,
            @Value("${ssccops.assistant.query.snippet-length}") int snippetLength) {

        this.topK = topK;
        this.similarityThreshold = similarityThreshold;
        this.maxQuestionLength = maxQuestionLength;
        this.snippetLength = snippetLength;
        thresholdByType.put(
                RagDocumentType.STRUCTURED,
                structuredThreshold == null ? similarityThreshold : structuredThreshold);
        thresholdByType.put(
                RagDocumentType.GENERIC,
                genericThreshold == null ? similarityThreshold : genericThreshold);

        log.info(
                "규정 도우미 질의 정책 — topK={} 임계값(기본={} 조단위={} 평문={}) 질문상한={}자",
                topK,
                similarityThreshold,
                thresholdByType.get(RagDocumentType.STRUCTURED),
                thresholdByType.get(RagDocumentType.GENERIC),
                maxQuestionLength);
    }

    /** 그 유형의 청크가 답변에 닿기 위해 넘어야 하는 점수 */
    public double thresholdFor(RagDocumentType type) {
        return thresholdByType.getOrDefault(type, similarityThreshold);
    }

    /**
     * 벡터 검색에 거는 임계값 — <b>유형별 값 중 가장 낮은 것</b>.
     *
     * <p>저장소는 요청 하나에 임계값 하나만 받으므로 여기서 가장 느슨한 값으로 긁고, 유형별 판정은 결과를 받은 뒤에 한다. 순서를 뒤집으면(가장 높은 값으로 긁으면)
     * 낮은 임계값을 가진 유형의 청크가 <b>애초에 돌아오지 않아</b> 그 손잡이가 아무 일도 하지 않는다.
     *
     * <p><b>판본 조건(`INDEXED && EFFECTIVE`)과 갈리는 지점이다.</b> 그쪽은 «보면 안 되는 것»이라 반드시 검색 필터이고(조회 뒤 {@code
     * if}가 아니다), 이쪽은 «얼마나 관련 있어야 하는가»라 두 단계로 나뉘어도 새어 나갈 것이 없다.
     */
    public double searchThreshold() {
        return thresholdByType.values().stream()
                .mapToDouble(Double::doubleValue)
                .min()
                .orElse(similarityThreshold);
    }
}
