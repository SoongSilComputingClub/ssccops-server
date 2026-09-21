package org.sscc.ssccopsserver.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.domain.notification.code.NotificationApp;
import org.sscc.ssccopsserver.domain.notification.code.NotificationTargetType;
import org.sscc.ssccopsserver.domain.notification.code.NotificationType;
import org.sscc.ssccopsserver.domain.notification.entity.NotificationEntity;
import org.sscc.ssccopsserver.domain.notification.repository.NotificationRepository;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.entity.WorkType;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkTypeRepository;
import org.sscc.ssccopsserver.domain.operation.service.SubWorkService;
import org.sscc.ssccopsserver.domain.operation.service.WorkService;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.SubWorkTypeFixture;

/*
 * 마감 알림의 판정과 중복 방지 (ssccops#446 수용 기준 3).
 *
 * 스케줄러(`DeadlineNotificationScheduler`)는 test 프로필에서 서지 않고(시각만 아는 빈이다) 서비스를
 * 직접 부른다. 공용 컨텍스트의 Clock은 벽시계라 «내일·어제»를 그 Clock으로 계산한다 — 날짜 경계를
 * 넘는 순간에 도는 테스트는 한 번 흔들릴 수 있으나 09:00 스케줄러의 실제 조건과 같다.
 *
 * @Transactional이다 — 리포지토리 save가 테스트 트랜잭션에 참여하므로 «두 번 불러도 한 번»은
 * exists 검사가 막는 경로를 본다(UNIQUE 위반 경로는 트랜잭션을 오염시키므로 여기서 보지 않는다 —
 * 제약 자체는 FlywayMigrationValidateTest·엔티티 선언이 지킨다).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DeadlineNotificationServiceTest {

    @Autowired private DeadlineNotificationService deadlineNotificationService;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private WorkService workService;
    @Autowired private SubWorkService subWorkService;
    @Autowired private SubWorkTypeRepository subWorkTypeRepository;
    @Autowired private Clock clock;

    private MemberEntity owner;
    private MemberEntity registrant;
    private Long workId;
    private Long typeId;

    @BeforeEach
    void setUp() {
        owner =
                MemberFixture.save(
                        memberRepository,
                        memberGradeRepository,
                        memberStatusRepository,
                        UUID.randomUUID(),
                        "20200001",
                        "김도현",
                        "owner@sscc.org");
        registrant =
                MemberFixture.save(
                        memberRepository,
                        memberGradeRepository,
                        memberStatusRepository,
                        UUID.randomUUID(),
                        "20200002",
                        "이서연",
                        "actor@sscc.org");
        workId =
                workService
                        .createWork(
                                new WorkCreateRequest(
                                        "2026 동아리 박람회",
                                        WorkType.EVENT,
                                        owner.getId(),
                                        null,
                                        null,
                                        null,
                                        null),
                                registrant)
                        .workId();
        typeId = SubWorkTypeFixture.idOf(subWorkTypeRepository, SubWorkTypeFixture.EXPENDITURE);
    }

    @Test
    void dueTomorrowGetsD1AndDueYesterdayGetsOverdueOnceEach() {
        LocalDate today = LocalDate.now(clock);
        Long dueTomorrow = createSubWork("내일 마감", today.plusDays(1));
        Long dueYesterday = createSubWork("어제 마감", today.minusDays(1));
        createSubWork("모레 마감", today.plusDays(2));
        createSubWork("오늘 마감", today);
        Long deleted = createSubWork("지운 건", today.plusDays(1));
        subWorkService.deleteSubWork(deleted);

        int created = deadlineNotificationService.notifyDeadlines();

        assertThat(created).isEqualTo(2);
        List<NotificationEntity> mine = mine();
        assertThat(mine).hasSize(2);
        NotificationEntity due =
                mine.stream()
                        .filter(n -> n.getTargetId().equals(dueTomorrow))
                        .findFirst()
                        .orElseThrow();
        assertThat(due.getType()).isEqualTo(NotificationType.DEADLINE_DUE);
        assertThat(due.getTitle()).isEqualTo("[마감 D-1] 내일 마감");
        assertThat(due.getBody()).isEqualTo("2026 동아리 박람회 · 담당 김도현 · 마감 " + today.plusDays(1));
        assertThat(due.getNotificationKey())
                .isEqualTo("DEADLINE_DUE:SUB_WORK:" + dueTomorrow + ":" + today.plusDays(1));
        assertThat(due.getLinkPath()).isEqualTo("/operations/sub-works/" + dueTomorrow);
        assertThat(due.getApp()).isEqualTo(NotificationApp.ADMIN);
        assertThat(due.getTargetType()).isEqualTo(NotificationTargetType.SUB_WORK);

        NotificationEntity overdue =
                mine.stream()
                        .filter(n -> n.getTargetId().equals(dueYesterday))
                        .findFirst()
                        .orElseThrow();
        assertThat(overdue.getType()).isEqualTo(NotificationType.DEADLINE_OVERDUE);
        assertThat(overdue.getTitle()).isEqualTo("[지연] 어제 마감");
        assertThat(overdue.getNotificationKey())
                .isEqualTo("DEADLINE_OVERDUE:SUB_WORK:" + dueYesterday + ":" + today.minusDays(1));

        // 같은 날 다시 돌아도(재기동·중복 실행) 두 번 가지 않는다
        assertThat(deadlineNotificationService.notifyDeadlines()).isZero();
        assertThat(mine()).hasSize(2);
    }

    @Test
    void purgeDeletesOnlyReadNotificationsOlderThanNinetyDays() {
        Instant now = clock.instant();
        NotificationEntity oldRead = save("옛 읽음");
        oldRead.markRead(now.minus(DeadlineNotificationService.READ_RETENTION).minusSeconds(60));
        NotificationEntity recentRead = save("최근 읽음");
        recentRead.markRead(now.minusSeconds(60));
        NotificationEntity oldUnread = save("옛 안 읽음");
        notificationRepository.flush();

        int deleted = deadlineNotificationService.purgeReadNotifications();

        assertThat(deleted).isEqualTo(1);
        assertThat(notificationRepository.findById(oldRead.getId())).isEmpty();
        assertThat(notificationRepository.findById(recentRead.getId())).isPresent();
        assertThat(notificationRepository.findById(oldUnread.getId()))
                .as("안 읽은 알림은 남긴다 (ADR-0045)")
                .isPresent();
    }

    /* ── helpers ──────────────────────────────────────────────── */

    private Long createSubWork(String title, LocalDate dueDate) {
        OffsetDateTime dueAt = dueDate.atTime(18, 0).atZone(clock.getZone()).toOffsetDateTime();
        return subWorkService
                .createSubWork(
                        new SubWorkCreateRequest(
                                workId,
                                title,
                                typeId,
                                owner.getId(),
                                null,
                                null,
                                dueAt,
                                null,
                                null,
                                null),
                        registrant)
                .subWorkId();
    }

    private List<NotificationEntity> mine() {
        return notificationRepository.findAll().stream()
                .filter(n -> n.getMember().getId().equals(owner.getId()))
                .toList();
    }

    private NotificationEntity save(String title) {
        return notificationRepository.save(
                NotificationEntity.create(
                        owner,
                        NotificationType.DEADLINE_DUE,
                        title,
                        "본문",
                        NotificationApp.ADMIN,
                        "/operations/sub-works/1",
                        NotificationTargetType.SUB_WORK,
                        1L,
                        null));
    }
}
