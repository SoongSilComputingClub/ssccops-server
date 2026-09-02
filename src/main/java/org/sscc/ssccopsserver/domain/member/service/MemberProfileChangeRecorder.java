package org.sscc.ssccopsserver.domain.member.service;

import java.util.Objects;

import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.member.code.MemberChangeField;
import org.sscc.ssccopsserver.domain.member.entity.MemberChangeHistoryEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberChangeHistoryRepository;

/*
 * 회원 정보 변경을 mbr_chg_hstry에 남기는 **유일한 자리** (#226).
 *
 * ── 왜 한 곳인가 ──────────────────────────────────────────────
 * 회원 정보를 고치는 경로는 둘이다 — 운영진(PATCH /v1/members/{mbrId}, 아홉 항목)과
 * 본인(PATCH /v1/members/me, 네 항목). 비교·기록 규칙이 경로마다 있으면 **무엇이 이력에
 * 남는지가 경로에 따라 갈린다.** 그렇게 갈린 이력은 "이 회원 정보가 언제 어떻게 바뀌었는가"에
 * 답하지 못하고, 답하지 못하는 이력을 근거로 학번 잠금을 풀 수는 없다.
 *
 * 경로가 갈리는 것은 **어느 항목을 바꿀 수 있는가**뿐이고 그것은 요청 DTO가 정한다. 여기는
 * 손에 든 두 사본(수정 전·후)을 비교할 뿐이라, 본인 경로가 못 고치는 항목은 애초에 값이 같아
 * 행이 만들어지지 않는다 — "본인이면 이 항목은 건너뛴다" 같은 분기가 필요 없다.
 *
 * ── 바뀐 항목만 남긴다 ────────────────────────────────────────
 * 두 수정 API는 전체 교체(PUT 의미)라 매번 모든 필드가 실려 온다. 값이 같아도 행을 만들면
 * 이름 하나 고친 저장이 이력 아홉 줄을 낳고, 그 목록에서는 실제로 무엇이 바뀌었는지 읽어 낼 수
 * 없다.
 *
 * **같은 값으로의 저장을 거절하지는 않는다.** 등급·상태는 같은 값이면 400 NO_CHANGE지만
 * (#78) 그쪽은 사유가 필수인 '사건'이고 이쪽은 여러 필드를 한 번에 보내는 폼 저장이다 —
 * 거절하면 학과만 고치는 저장이 통째로 막힌다. 아무것도 바뀌지 않았으면 조용히 한 행도 남기지
 * 않고 지나간다.
 *
 * ── 변경자는 인증 주체에서 온다 ───────────────────────────────
 * 요청 본문으로 받지 않는다 (#78 규칙). 받아 주면 스스로 적어 넣을 수 있어 이력이 증거가 되지
 * 못한다. 본인 수정이면 변경자가 본인이며, 그것은 예외가 아니라 사실 그대로다.
 *
 * **트랜잭션 경계를 스스로 열지 않는다.** mbr UPDATE와 같은 트랜잭션에 있어야 값만 바뀌고
 * 이력이 없는 반쪽 상태가 생기지 않으므로, 경계는 부르는 쪽(회원 정보 수정 서비스)이 정한다 —
 * 최초 이력 기록(MemberInitialHistoryRecorder)과 같은 배치다.
 */
@Component
public class MemberProfileChangeRecorder {

    private final MemberChangeHistoryRepository memberChangeHistoryRepository;

    public MemberProfileChangeRecorder(
            MemberChangeHistoryRepository memberChangeHistoryRepository) {
        this.memberChangeHistoryRepository = memberChangeHistoryRepository;
    }

    /*
     * 바뀐 항목마다 이력 한 줄. 행이 만들어지는 순서는 MemberChangeField의 선언 순서이며
     * (= mbr의 컬럼 순서), 그래서 한 번의 저장이 남긴 여러 줄이 늘 같은 차례로 쌓인다.
     *
     * **저장 전에 두 사본을 다 만들어 두어야 한다.** 수정 후 사본을 여기서 다시 뜨지 않고
     * 인자로 받는 것은, 부르는 쪽이 엔티티를 이미 바꾼 뒤이므로 '전'을 되찾을 방법이 없기
     * 때문이다 — 붙들어 두는 책임을 여기로 옮기면 그 순서를 지키는 자리가 다시 두 곳이 된다.
     */
    /**
     * @param before 수정 직전의 사본. 엔티티를 바꾸기 **전에** 떠 두어야 한다
     * @param after 수정 직후의 사본
     * @param changer 변경자(chnrg_mbr_id). 언제나 @CurrentMember에서 온다
     */
    public void record(
            MemberEntity member,
            MemberProfileSnapshot before,
            MemberProfileSnapshot after,
            MemberEntity changer) {

        for (MemberChangeField field : MemberChangeField.values()) {
            String previousContent = before.valueOf(field);
            String newContent = after.valueOf(field);

            // null끼리도 '같다'로 봐야 한다 — 비어 있던 값을 비운 채로 다시 저장한 경우다
            if (Objects.equals(previousContent, newContent)) {
                continue;
            }

            memberChangeHistoryRepository.save(
                    MemberChangeHistoryEntity.create(
                            member, field, previousContent, newContent, changer));
        }
    }
}
