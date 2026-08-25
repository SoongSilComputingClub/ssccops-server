package org.sscc.ssccopsserver.domain.form.service;

import java.util.Optional;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormLabelEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormLabelRelationEntity;
import org.sscc.ssccopsserver.domain.form.repository.FormLabelRelationRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormLabelRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
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
 * 생성자로 세울 사람이 없어 시드를 건너뛰고, 최초 가입자(#71 부트스트랩)가 생긴 뒤 다음
 * 기동에서 세워진다. 컬럼을 NULL 허용으로 바꾸는 쪽은 택하지 않았다 — 관리자 폼 목록 질의가
 * `join fetch f.creator`(내부 조인)라 생성자가 없는 폼은 **목록에서 통째로 사라진다.** 세우자마자
 * 운영진 눈에 보이지 않는 폼이 되는 셈이라, 컬럼을 여는 대가가 시드가 한 기동 늦는 것보다 크다.
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
     * 시드 본체. run()과 나눠 둔 것은 테스트가 기동 없이 이 자리를 직접 부르기 위해서다
     * (ApplicationRunner는 @DataJpaTest 컨텍스트에서 실행되지 않는다).
     */
    public void seed() {
        if (formRepository.findBySystemFormCode(ProposalFormSeed.SYSTEM_FORM_CODE).isPresent()) {
            return;
        }

        Optional<MemberEntity> creator = memberRepository.findTopByOrderByIdAsc();
        if (creator.isEmpty()) {
            log.info(
                    "회원이 없어 기획안 시스템 폼({}) 시드를 건너뛴다. 최초 가입자가 생긴 뒤 다음 기동에서 세워진다.",
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
