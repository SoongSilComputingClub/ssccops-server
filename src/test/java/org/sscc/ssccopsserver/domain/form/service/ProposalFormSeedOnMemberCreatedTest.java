package org.sscc.ssccopsserver.domain.form.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.sscc.ssccopsserver.domain.form.repository.FormLabelRelationRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.member.code.MemberGradeCode;
import org.sscc.ssccopsserver.domain.member.code.MemberStatusCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.support.MemberFixture;

/*
 * 회원이 생기는 순간 기획안 시스템 폼이 서는지 (#184).
 *
 * ── 이 테스트가 지키는 것 ──────────────────────────────────────
 * 시드가 기동 때만 돌던 동안 로컬은 폼을 세울 방법이 없었다. `local`은 ddl-auto가 create-drop이라
 * 기동마다 회원이 사라지는데, 회원이 없으면 시드는 건너뛰고(creatr_mbr_id가 NOT NULL이다) 그
 * 유일한 복구 수단인 재기동이 다시 회원을 지운다 — **어떤 순서로도 폼이 서지 못했다.**
 * 그래서 회원 생성 커밋 직후에도 시드를 태운다. 여기가 그 경로를 못 박는 자리다.
 *
 * ── @Transactional을 걸 수 없다 ────────────────────────────────
 * 리스너가 AFTER_COMMIT이라 **실제로 커밋되어야** 돈다. 테스트에 트랜잭션을 걸면 커밋이 일어나지
 * 않아 리스너가 한 번도 불리지 않고, 그런데도 테스트는 초록일 수 있다 — 검증하려는 경로를 아예
 * 지나지 않기 때문이다(ProposalMigrationRollbackTest와 같은 이유).
 *
 * ── 그래서 DB를 따로 쓴다 ──────────────────────────────────────
 * 커밋한 회원·폼이 남는다. 공용 H2(testdb)를 쓰면 "회원이 한 명도 없는 상태"를 전제하는 부트스트랩
 * 테스트가 실행 순서에 따라 깨지고, 반대로 이쪽도 남의 회원 탓에 전제가 흔들린다. URL을 바꿔 이
 * 클래스만의 DB를 띄우고, 매 테스트가 스스로 비우고 시작한다.
 */
@SpringBootTest(
        properties = {
            "spring.datasource.url="
                + "jdbc:h2:mem:proposal-seed-on-member-created;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            // application-test.yaml이 꺼 둔 경로다. 그것을 확인하는 유일한 자리라 여기서 켠다
            "ssccops.form.proposal-seed.on-member-created=true"
        })
@ActiveProfiles("test")
class ProposalFormSeedOnMemberCreatedTest {

    @Autowired private FormRepository formRepository;
    @Autowired private FormLabelRelationRepository formLabelRelationRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;

    /*
     * 로컬의 첫 기동 직후 상태로 되돌린다 — 기준 코드는 있고 회원도 폼도 없다. 순서를 정해 앞
     * 테스트의 결과에 기대는 대신 스스로 전제를 세우는 쪽을 골랐다. 라벨(form_lbl)은 지우지
     * 않는다 — 시더가 이름으로 찾아 재사용하므로 남아 있어도 결과가 달라지지 않고, 지우면 폼이
     * 아니라 라벨을 다시 만드는지를 함께 시험하는 셈이 되어 이 테스트의 초점이 흐려진다.
     */
    @BeforeEach
    void resetToFreshEnvironment() {
        formLabelRelationRepository.deleteAll();
        formRepository.deleteAll();
        memberRepository.deleteAll();
    }

    /*
     * 핵심. 재기동 없이, 가입 한 번으로 폼이 선다.
     */
    @Test
    void seedsTheProposalFormAsSoonAsTheFirstMemberIsCreated() {
        assertThat(formRepository.findBySystemFormCode(ProposalFormSeed.SYSTEM_FORM_CODE))
                .as("회원이 없는 환경에서는 기동 시드가 건너뛰어 폼이 없다")
                .isEmpty();

        saveMember("20260001", "김최초", "first@sscc.org");

        assertThat(formRepository.findBySystemFormCode(ProposalFormSeed.SYSTEM_FORM_CODE))
                .as("가입 커밋 직후 시드가 돌아 폼이 선다 — 재기동을 기다리지 않는다")
                .isPresent();
    }

    /*
     * 발행을 저장소 지점에 둔 이유를 못 박는다. 명부 이관(MemberImportRowExecutor, #84)은
     * save가 아니라 **saveAndFlush**로 저장하는데, Spring Data가 도메인 이벤트를 낼지 가리는
     * 판정은 메서드 이름이 "save"로 시작하는가다 — 그래서 이쪽도 함께 잡힌다. 이 규칙이
     * 무너지면 명부로만 회원을 만든 환경에 폼이 서지 않고, 그 사실은 기획안 화면을 열 때에야
     * 드러난다.
     */
    @Test
    void seedsWhenTheMemberIsStoredWithSaveAndFlushToo() {
        memberRepository.saveAndFlush(
                MemberEntity.create(
                        "20260002",
                        0,
                        "이이관",
                        null,
                        null,
                        null,
                        "imported@sscc.org",
                        memberGradeRepository.findById(MemberGradeCode.TEMP.code()).orElseThrow(),
                        memberStatusRepository
                                .findById(MemberStatusCode.ENROLLED.code())
                                .orElseThrow(),
                        LocalDate.now()));

        assertThat(formRepository.findBySystemFormCode(ProposalFormSeed.SYSTEM_FORM_CODE))
                .isPresent();
    }

    /*
     * 회원이 더 늘어도 폼은 하나다. 이벤트를 가입마다 받되 부트스트랩 여부로 좁히지 않기로 한
     * 판단이 성립하려면 여기가 지켜져야 한다 — 좁히지 않는 대신 멱등성에 기대는 구조다.
     * 두 번째 폼이 생기면 sys_form_cd의 UNIQUE에 걸려 두 번째 가입이 깨진다.
     */
    @Test
    void createsTheFormOnlyOnceHoweverManyMembersAreAdded() {
        saveMember("20260003", "김첫째", "one@sscc.org");
        saveMember("20260004", "박둘째", "two@sscc.org");
        saveMember("20260005", "최셋째", "three@sscc.org");

        assertThat(formRepository.findAll())
                .filteredOn(
                        form -> ProposalFormSeed.SYSTEM_FORM_CODE.equals(form.getSystemFormCode()))
                .hasSize(1);
        assertThat(memberRepository.count()).isEqualTo(3);
    }

    private void saveMember(String studentNumber, String name, String email) {
        MemberFixture.save(
                memberRepository,
                memberGradeRepository,
                memberStatusRepository,
                UUID.randomUUID(),
                studentNumber,
                name,
                email);
    }
}
