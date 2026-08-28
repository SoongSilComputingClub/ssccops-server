package org.sscc.ssccopsserver.domain.form.service;

import java.util.Optional;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormLabelEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormLabelRelationEntity;
import org.sscc.ssccopsserver.domain.form.repository.FormLabelRelationRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormLabelRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.event.MemberCreatedEvent;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/*
 * 기획안 시스템 폼(sys_form_cd = 'PROPOSAL') 시드 (#173).
 *
 * 문항 구성과 qitemId 계약은 ProposalFormSeed가 갖고, 여기서는 그것을 **한 번만** 세운다.
 * data.sql이 아니라 자바인 이유는 ProposalFormSeed 주석에 적어 뒀다(JSONB 리터럴을 H2와
 * PostgreSQL이 다르게 읽어 한 벌의 SQL로는 양쪽을 만족시킬 수 없다).
 *
 * ── 멱등 ───────────────────────────────────────────────────
 * 판정은 sys_form_cd 하나로 한다 — form_id는 IDENTITY라 환경마다 다르고 제목·라벨은 화면에서
 * 바뀌는 운영 데이터라, 그 둘로 "이미 있는가"를 물으면 제목을 고친 다음 기동에서 폼이 하나 더
 * 생긴다(#140의 판단 그대로다). 이미 있으면 **아무것도 하지 않는다.** 값이 다르다고 UPDATE로
 * 덮어쓰지 않는 것은 data.sql 첫 줄의 규칙과 같다 — 운영진이 회차마다 손댄 문구·문항·상태를
 * 배포가 조용히 되돌리면, 잠금을 삭제와 계약 위반으로만 좁혀 둔 것(BR-M33)이 무의미해진다.
 *
 * ── 상태는 DRAFT, 접수 기간은 비워 둔다 ────────────────────
 * **접수를 여는 것은 운영진의 조작이지 배포의 부작용이 아니다.** OPEN으로 시드하면 이 코드가
 * 나가는 순간 아무도 누르지 않은 접수가 열리고, 회차마다 다른 접수 기간은 어차피 운영진이
 * 정해야 하는 값이다. 기간이 비어 있는 것은 '제한 없음'을 뜻하므로(FormReceiptPolicy) 값을
 * 미리 넣어 두면 그 자체가 잘못된 안내가 된다.
 *
 * ── 회원이 없으면 세우지 않는다 ─────────────────────────────
 * form.creatr_mbr_id는 NOT NULL이고 mbr을 가리킨다. 회원이 한 명도 없는 갓 만들어진 환경에는
 * 생성자로 세울 사람이 없어 시드를 건너뛴다. 컬럼을 NULL 허용으로 바꾸는 쪽은 택하지 않았다 —
 * 관리자 폼 목록 질의가 `join fetch f.creator`(내부 조인)라 생성자가 없는 폼은 **목록에서 통째로
 * 사라지고**, 제약 변경은 ddl-auto: update가 반영하지 않아 dev·prod 수동 DDL이 따라붙는다.
 *
 * ── 그래서 도는 자리가 둘이다 (#184) ────────────────────────
 * 기동만으로는 부족하다. 최초 가입자(#71 부트스트랩)가 생겨도 시더는 이미 지나간 뒤라 다음
 * 기동까지 폼이 없는데, **로컬은 ddl-auto가 create-drop이라 그 '다음 기동'이 방금 만든 회원을
 * 함께 지운다.** 유일한 복구 수단인 재기동이 복구의 전제 조건을 지우는 셈이라, 로컬에서는 어떤
 * 순서로도 폼이 서지 못했다. dev·prod에서도 최초 구축 직후 재기동 전까지는 같은 구멍이 있다.
 *
 * 그래서 회원이 생기는 순간에도 시드를 태운다(onMemberCreated). 두 자리가 같은 seed()를 부르고,
 * 멱등성이 겹침을 흡수한다. 기동 경로를 남겨 두는 것은 **회원은 있는데 폼이 없는 기존 환경**
 * 때문이다 — 그런 DB에는 가입 이벤트가 오지 않아 기동이 유일한 기회다.
 *
 * ── 동시 기동 ──────────────────────────────────────────────
 * 인스턴스가 둘 이상이면 두 번 세우려 할 수 있고, 그때는 uk_form_sys_form_cd가 최종 방어선이 되어
 * 늦은 쪽이 실패한다. 조회 후 삽입 사이의 경합을 애플리케이션에서 막지 않는 것은 data.sql의
 * `WHERE NOT EXISTS`도 똑같이 막지 못하기 때문이고, 이 서비스가 단일 인스턴스로 뜨기 때문이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProposalFormSeeder implements ApplicationRunner {

    private final FormRepository formRepository;
    private final FormLabelRepository formLabelRepository;
    private final FormLabelRelationRepository formLabelRelationRepository;
    private final MemberRepository memberRepository;
    private final QuestionCompositionValidator questionCompositionValidator;
    private final PlatformTransactionManager transactionManager;

    /*
     * 트랜잭션 경계를 run()에 둔다. 폼·라벨·연결 세 행이 한 번에 들어가야 하며, 각 save()가
     * 자기 트랜잭션으로 흩어지면 라벨 지정에 실패했을 때 라벨 없는 시스템 폼이 남는다.
     */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seed();
    }

    /*
     * 회원이 생긴 직후의 두 번째 기회 (#184). 이 자리가 로컬의 데드락을 푼다 — 가입하는 순간
     * 폼이 서고, 재기동으로 스키마가 날아가면 다음 가입 때 다시 선다.
     *
     * ── AFTER_COMMIT이어야 하는 이유 ───────────────────────────
     * 가입 트랜잭션이 롤백되면 생성자로 삼은 회원이 사라지는데 폼만 남는다. 커밋을 보고 움직인다.
     *
     * ── 왜 @Transactional이 아니라 TransactionTemplate인가 ─────
     * 두 가지를 동시에 지켜야 해서다.
     *
     * 하나, **새 트랜잭션이어야 한다.** AFTER_COMMIT 시점에는 방금 커밋된 트랜잭션의 자원이 아직
     * 묶여 있어, 그냥 참여하면(PROPAGATION_REQUIRED) 이미 끝난 트랜잭션에 쓰기를 얹는 꼴이 되어
     * 시드가 반영되지 않는다. REQUIRES_NEW로 자기 트랜잭션을 연다.
     *
     * 둘, **예외가 가입을 깨뜨리면 안 된다.** AFTER_COMMIT 리스너의 예외는 삼켜지지 않고
     * commit()을 부른 쪽으로 올라간다 — 데이터는 이미 커밋됐는데 가입 API만 500으로 떨어지는,
     * 가장 나쁜 모양이다. 그래서 예외를 잡되 **트랜잭션 경계 바깥에서** 잡아야 한다. 커밋 자체가
     * 실패하는 경우까지 덮으려면 try가 커밋을 감싸야 하는데, 메서드에 @Transactional을 걸면
     * 커밋은 프록시가 이 메서드를 빠져나간 뒤에 일어나 안쪽 try로는 잡히지 않는다. 같은 빈의
     * 메서드를 불러 프록시를 태울 수도 없다(자기 호출은 프록시를 지나지 않는다). 경계를 코드로
     * 들고 있는 TransactionTemplate이 이 두 요구를 한 자리에서 만족시킨다.
     *
     * 시드에 실패해도 폼이 없을 뿐이고 기동 경로와 다음 가입이 다시 시도한다. 가입은 이 폼과
     * 무관하게 성공해야 하는 별개의 일이다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMemberCreated(MemberCreatedEvent event) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            transaction.executeWithoutResult(status -> seed());
        } catch (RuntimeException e) {
            log.error(
                    "회원 생성(mbrId={}) 뒤 기획안 시스템 폼({}) 시드에 실패했다. 가입은 정상 처리됐으며,"
                            + " 다음 가입이나 다음 기동에서 다시 시도한다.",
                    event.memberId(),
                    ProposalFormSeed.SYSTEM_FORM_CODE,
                    e);
        }
    }

    /*
     * 시드 본체. run()과 나눠 둔 것은 테스트가 기동 없이 이 자리를 직접 부르기 위해서다
     * (ApplicationRunner는 @DataJpaTest 컨텍스트에서 실행되지 않는다).
     */
    public void seed() {
        if (formRepository.findBySystemFormCode(ProposalFormSeed.SYSTEM_FORM_CODE).isPresent()) {
            return;
        }

        /*
         * 건너뛴 것은 정상 상태가 아니라 '아직 못 세운' 상태라 warn으로 올린다 (#184). info로
         * 두었더니 로컬 기동 로그에 묻혀, 폼이 없는 것을 시드가 아니라 data.sql의 누락으로
         * 오진하게 만들었다 — 이 이슈가 그 제보에서 시작했다.
         */
        Optional<MemberEntity> creator = memberRepository.findTopByOrderByIdAsc();
        if (creator.isEmpty()) {
            log.warn(
                    "회원이 없어 기획안 시스템 폼({}) 시드를 건너뛴다. 최초 가입자가 생기는 즉시 세워진다.",
                    ProposalFormSeed.SYSTEM_FORM_CODE);
            return;
        }

        /*
         * 시드 구성도 저장 경로와 **같은 검증기**를 지난다. 여기서 걸러 두지 않으면 문항 구성이
         * 스스로 모순된 폼(선택지 없는 선택형, 없는 페이지로의 분기)이 그대로 들어가고, 그 사실은
         * 운영진이 접수를 열려는 순간에야 드러난다 — 시드가 깨진 채 배포되면 폼을 열 수 없다.
         * ProposalFormSeedTest가 같은 검증을 기동 전에 한 번 더 한다.
         */
        FormEntity form =
                FormEntity.create(
                        creator.get(),
                        ProposalFormSeed.FORM_TITLE,
                        questionCompositionValidator.validate(ProposalFormSeed.composition()),
                        null,
                        null,
                        FormStatus.DRAFT,
                        /*
                         * 한 사람이 스터디와 프로젝트를 각각 내는 것이 정상이다 (#143).
                         * 폼의 성격에서 유추하지 않고 값으로 두는 이유는 FormEntity 주석에 있다.
                         */
                        true);
        form.designateAsSystemForm(ProposalFormSeed.SYSTEM_FORM_CODE);
        formRepository.save(form);

        formLabelRelationRepository.save(FormLabelRelationEntity.create(form, label()));

        log.info(
                "기획안 시스템 폼({})을 시드했다. 접수 상태는 DRAFT이며 여는 것은 운영진의 조작이다.",
                ProposalFormSeed.SYSTEM_FORM_CODE);
    }

    /*
     * 라벨은 이름으로 찾아 없을 때만 만든다. form_lbl은 화면(#34)에서 추가·개명하는 운영
     * 데이터라 식별자를 코드에 박을 수 없고, lbl_nm에 UNIQUE가 걸려 있어 이름이 곧 키다 —
     * data.sql이 역할·권한을 role_id가 아니라 role_nm으로 찾아 넣는 것과 같은 모양이다.
     *
     * 라벨을 지웠다 다시 만드는 운영자를 상정하지 않는다(#34는 삭제 대신 use_yn을 내린다).
     * 비활성 라벨이라도 그대로 쓴다 — 이미 있는 이름 위에 같은 이름을 하나 더 만들면 UNIQUE에
     * 걸리고, 활성 여부는 새로 달 수 있는가의 문제라 이 시드가 판단할 일이 아니다.
     */
    private FormLabelEntity label() {
        return formLabelRepository
                .findByName(ProposalFormSeed.LABEL_NAME)
                .orElseGet(
                        () ->
                                formLabelRepository.save(
                                        FormLabelEntity.create(ProposalFormSeed.LABEL_NAME)));
    }
}
