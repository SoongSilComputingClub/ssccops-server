package org.sscc.ssccopsserver.domain.member.service;

import java.time.LocalDate;

import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberGradeEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberGradeHistoryEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberStatusEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberStatusHistoryEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeHistoryRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusHistoryRepository;

/*
 * 회원이 생길 때 등급·상태의 **최초 이력**을 남기는 유일한 자리 (#21 · #85 · BR-M47).
 *
 * 원래 MemberServiceImpl.recordInitialHistories였는데, mbr 행을 만드는 경로가 가입 하나에서
 * 가입·CSV 이관 둘로 늘면서 꺼냈다. 복제하면 한쪽만 이력을 남기거나 bfr_*의 뜻이 갈리게 되고,
 * 그때부터 회원 상세의 변경이력은 "언제 무엇으로 시작했는지"를 어떤 회원에게는 보여주고 어떤
 * 회원에게는 보여주지 못한다.
 *
 * ── 두 경로가 갈리는 것은 '변경자'와 '사유' 둘뿐이다 ──────────────────
 * 가입은 본인 신청이라 chnrg_mbr_id가 본인이고 사유가 '회원가입'이다. 이관은 운영자가 한 조작이라
 * chnrg_mbr_id가 **요청한 운영자**이고 사유가 'CSV 이관'이다 — 그래야 "이 사람 등급은 누가
 * 정했나"에 답할 수 있다. 그래서 이 메서드는 둘을 인자로 받는다.
 *
 * bfr_*는 언제나 NULL이다. 회원이 생기기 전에는 등급도 상태도 없었다는 사실 그대로이며, 여기에
 * 값을 넣기 시작하면 첫 승급 이력의 이전 등급이 근거 없이 떠 있게 된다.
 *
 * **트랜잭션 경계를 스스로 열지 않는다.** 회원 INSERT와 같은 트랜잭션에 있어야 회원만 남고 이력이
 * 없는 반쪽 상태가 생기지 않으므로, 경계는 부르는 쪽(가입 서비스 · 이관의 행 처리기)이 정한다.
 */
@Component
public class MemberInitialHistoryRecorder {

    private final MemberGradeHistoryRepository memberGradeHistoryRepository;
    private final MemberStatusHistoryRepository memberStatusHistoryRepository;

    public MemberInitialHistoryRecorder(
            MemberGradeHistoryRepository memberGradeHistoryRepository,
            MemberStatusHistoryRepository memberStatusHistoryRepository) {
        this.memberGradeHistoryRepository = memberGradeHistoryRepository;
        this.memberStatusHistoryRepository = memberStatusHistoryRepository;
    }

    /**
     * @param appliedDate 적용일. 두 경로 모두 회원의 가입일(mbr.join_ymd)이다
     * @param changeReason 변경 사유. 가입은 '회원가입', 이관은 'CSV 이관'
     * @param changedBy 변경자(chnrg_mbr_id). 가입은 본인, 이관은 요청한 운영자
     */
    public void record(
            MemberEntity member,
            MemberGradeEntity grade,
            MemberStatusEntity status,
            LocalDate appliedDate,
            String changeReason,
            MemberEntity changedBy) {

        memberGradeHistoryRepository.save(
                MemberGradeHistoryEntity.create(
                        member, null, grade, appliedDate, changeReason, changedBy));
        memberStatusHistoryRepository.save(
                MemberStatusHistoryEntity.create(
                        member, null, status, appliedDate, null, changeReason, changedBy));
    }
}
