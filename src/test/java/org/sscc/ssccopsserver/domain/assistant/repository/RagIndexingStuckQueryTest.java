package org.sscc.ssccopsserver.domain.assistant.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.sscc.ssccopsserver.domain.assistant.code.RagDocumentType;
import org.sscc.ssccopsserver.domain.assistant.code.RagIndexStatus;
import org.sscc.ssccopsserver.domain.assistant.entity.RagDocumentEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.global.config.JpaAuditingConfig;
import org.sscc.ssccopsserver.global.config.JsonFormatMapperConfig;
import org.sscc.ssccopsserver.support.MemberFixture;

/*
 * 기동 복구가 «멈춘 것»으로 보는 조건을 못 박는다 (#556 · ssccops#501).
 *
 * ── 왜 나이 조건이 필요한가 ────────────────────────────────────────────────
 * 그전에는 `findIdsByIndexStatus(INDEXING)` 로 **상태만** 보고 전부 되돌렸다. 그 설계는 «인스턴스가
 * 하나»를 전제로 했는데, **Coolify 는 새 컨테이너를 띄워 헬스체크를 통과시킨 뒤 옛 것을 내리므로
 * 겹침은 사고가 아니라 배포 절차 그 자체**다. 배포는 `develop` 푸시마다 일어난다.
 *
 * 그래서 새로 뜬 인스턴스의 부팅 복구가 **지금 돌고 있는 색인을 되돌렸다.** 옛 인스턴스는 임베딩을
 * 마치고 결과를 적으려다 상태가 `INDEXING` 이 아니라 그냥 지나가고, 청크는 이미 들어가 있어 고아가
 * 된다. 그리고 새 인스턴스가 같은 문서를 처음부터 다시 임베딩한다 — 무료 티어 하루치가 두 번 나간다.
 *
 * **아래 첫 테스트가 그 사건을 재현한다.**
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({JpaAuditingConfig.class, JsonFormatMapperConfig.class})
class RagIndexingStuckQueryTest {

    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");

    /** 기동 복구의 기준선 — 이보다 앞서 집힌 행만 «멈춘 것»이다(기본 `PT10M`). */
    private static final Instant TEN_MINUTES_AGO = NOW.minusSeconds(600);

    @Autowired private RagDocumentRepository ragDocumentRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;

    private MemberEntity registrant;

    @BeforeEach
    void setUp() {
        registrant =
                MemberFixture.save(
                        memberRepository,
                        memberGradeRepository,
                        memberStatusRepository,
                        UUID.randomUUID(),
                        "20260101",
                        "홍길동",
                        "20260101@soongsil.ac.kr");
    }

    /*
     * ⚠️ **이 테스트가 이 변경의 이유다.**
     *
     * 30초 전에 집힌 문서는 **다른 인스턴스가 지금 색인 중**이라는 뜻이다. 조건 없는 옛 질의는
     * 이것을 되돌렸고, 그 결과가 고아 청크와 재임베딩이었다.
     */
    @Test
    void doesNotRecoverDocumentsPickedUpJustNow() {
        saveIndexing("방금 집힌 회칙", NOW.minusSeconds(30));

        Assertions.assertThat(stuckIds()).isEmpty();
    }

    /* 10분을 넘겨 멈춰 있으면 진짜로 죽은 작업이다 — 그것은 되돌린다. */
    @Test
    void recoversDocumentsStuckLongerThanTheWindow() {
        RagDocumentEntity stuck = saveIndexing("어제 죽은 색인", NOW.minusSeconds(3600));

        Assertions.assertThat(stuckIds()).containsExactly(stuck.getId());
    }

    /*
     * 색인 시작 시각이 비어 있으면 **고르지 않는다.**
     *
     * 언제 집혔는지 모르는 행을 «오래됐다»고 볼 수 없다. 조건을 느슨하게 해 «시각이 없으면
     * 되돌린다»로 두면 이 변경이 막으려는 바로 그 자리(진행 중인 작업을 되돌리는 것)가 되살아난다.
     */
    @Test
    void ignoresDocumentsWithoutAStartTimestamp() {
        RagDocumentEntity noTimestamp =
                ragDocumentRepository.saveAndFlush(
                        RagDocumentEntity.register(
                                "시작 시각이 없는 문서",
                                RagDocumentType.STRUCTURED,
                                "x.pdf",
                                10,
                                registrant));
        Assertions.assertThat(noTimestamp.getIndexStatus()).isEqualTo(RagIndexStatus.PENDING);

        Assertions.assertThat(stuckIds()).isEmpty();
    }

    /* 다른 상태의 행은 나이와 무관하게 건드리지 않는다 — 복구는 `INDEXING` 만의 일이다. */
    @Test
    void ignoresDocumentsInOtherStatuses() {
        ragDocumentRepository.saveAndFlush(
                RagDocumentEntity.register(
                        "대기 중", RagDocumentType.STRUCTURED, "y.pdf", 10, registrant));

        Assertions.assertThat(stuckIds()).isEmpty();
    }

    private List<Long> stuckIds() {
        return ragDocumentRepository.findIdsStuckInStatusSince(
                RagIndexStatus.INDEXING, TEN_MINUTES_AGO);
    }

    private RagDocumentEntity saveIndexing(String name, Instant startedAt) {
        RagDocumentEntity document =
                RagDocumentEntity.register(
                        name, RagDocumentType.STRUCTURED, name + ".pdf", 1024, registrant);
        document.startIndexing(startedAt);
        return ragDocumentRepository.saveAndFlush(document);
    }
}
