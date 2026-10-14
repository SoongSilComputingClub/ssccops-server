package org.sscc.ssccopsserver.domain.form.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseHistoryRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 접수가 끝난 폼에 남은 미제출 초안을 지운다 (#557 · ssccops#502 · #36 결정).
 *
 * <p><b>왜 스케줄러와 다른 빈인가 — 처음에는 같은 클래스였고, 그래서 트랜잭션이 걸리지 않았다</b> (#570). {@code @Scheduled} 메서드가 같은
 * 클래스의 {@code @Transactional} 메서드를 {@code this}로 부르면 Spring 프록시를 지나지 않아 <b>애노테이션이 아무 일도 하지 않는다.</b>
 * 아래 삭제는 {@code @Modifying} 벌크 질의라 트랜잭션이 없으면 {@code TransactionRequiredException}으로 죽는데, 그 예외를
 * 스케줄러의 {@code catch}가 받아 <b>주 1회 로그 한 줄로만 남는다</b> — 정책이 실행되지 않는 채로 조용히 지나갈 자리였다.
 *
 * <p>같은 도메인의 {@code DeadlineNotificationScheduler}는 처음부터 이 모양이다(스케줄러가 서비스 빈을 주입받아 부른다). 그쪽과 같게 맞춘
 * 것이지 새 구조를 들인 것이 아니다.
 *
 * <p><b>테스트가 못 잡은 이유도 적어 둔다.</b> {@code FormDraftRetentionQueryTest}는 {@code @DataJpaTest}라 <b>테스트
 * 자체가 트랜잭션 안에서 돈다</b> — 질의는 정상으로 보인다. 빠진 것은 질의가 아니라 배선이었고, 그것은 질의 테스트가 볼 수 있는 종류가 아니다. Sonar의
 * {@code java:S2229}·{@code java:S6809}가 잡았다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FormDraftRetentionService {

    /**
     * 접수 종료 뒤 이만큼 지난 폼의 초안을 지운다.
     *
     * <p>90일은 #36의 결정값이고 알림 정리(읽은 지 90일)와 같은 길이다. 설정 손잡이로 빼지 않은 것은 이 값이 «운영 편의»가 아니라 <b>보관 근거의
     * 기한</b>이라 환경마다 달라지면 안 되기 때문이다 — dev에서 30일로 줄여 두면 그것이 곧 다른 정책이 된다.
     */
    private static final Duration DRAFT_RETENTION = Duration.ofDays(90);

    /**
     * 한 번에 지우는 초안 수. IN 절의 바인드 변수가 이보다 많아지지 않게 끊는다 — JDBC 드라이버·DB 프로토콜에는 한 문장의 바인드 변수 수에 상한이 있어 초안이
     * 몰린 주에 그 상한에 닿으면 정리 전체가 실패한다. 주 1회 정리라 실제로는 한 묶음이면 끝나는 양이다.
     */
    private static final int DELETE_CHUNK_SIZE = 500;

    private final FormResponseHistoryRepository formResponseHistoryRepository;

    /*
     * 지우는 초안에 붙은 기획안 첨부(file_rfrnc · FORM_RESPONSE)를 함께 걷는다 (server#685). file_rfrnc에 FK가
     * 없어 응답 행만 지우면 첨부 행과 R2 오브젝트가 고아로 남는다 — 초안은 «지원 의사를 남기지 않은 개인정보»라
     * 지우는 것인데(#36) 그 사람이 올린 파일만 버킷에 남으면 정책이 반쪽이 된다. 오브젝트는 커밋 뒤에 지운다.
     */
    private final ProposalAttachmentService proposalAttachmentService;

    private final Clock clock;

    /**
     * 실제 삭제.
     *
     * <p>지울 초안의 식별자를 먼저 모은다 — 첨부를 함께 걷으려면 무엇을 지웠는지 알아야 하는데 벌크 DELETE는 건수만 돌려준다(저장소 주석). 첨부와 응답은 같은
     * 트랜잭션이라 응답 삭제가 실패하면 첨부 행도 되돌아가고, 오브젝트는 커밋된 뒤에만 지워진다.
     *
     * @return 지운 초안 수
     */
    @Transactional
    public int purge() {
        Instant before = clock.instant().minus(DRAFT_RETENTION);
        List<Long> draftIds =
                formResponseHistoryRepository.findDraftIdsOfFormsClosedBefore(
                        ResponseStatus.DRAFT, FormStatus.CLOSED, before);
        int deleted = 0;
        for (int from = 0; from < draftIds.size(); from += DELETE_CHUNK_SIZE) {
            List<Long> chunk =
                    draftIds.subList(from, Math.min(from + DELETE_CHUNK_SIZE, draftIds.size()));
            proposalAttachmentService.deleteAllOfResponses(chunk);
            deleted +=
                    formResponseHistoryRepository.deleteDraftsByIdIn(chunk, ResponseStatus.DRAFT);
        }
        if (deleted > 0) {
            // 건수를 남기는 것은 처음 도는 날 «몇 건이 지워졌나»가 어디에도 없으면 되짚을 수
            // 없기 때문이다. 초안 내용·회원은 싣지 않는다(ADR-0024).
            log.info("closed form drafts purged: {} (기준 {})", deleted, before);
        }
        return deleted;
    }
}
