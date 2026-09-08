package org.sscc.ssccopsserver.domain.operation.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * sub_work_chck_list_hstry(하위 업무 점검 목록 이력) — 완료 조건이 달라질 때마다 한 건씩
 * 쌓이는 불변 이력 (#307).
 *
 * **이 테이블이 #307의 중심이다.** 항목 편집을 기획·진행으로 좁히고 체크된 항목의 삭제를
 * 막아도, 기획·진행 단계에서 항목을 지우는 것은 여전히 가능하고 그건 정당한 행동이다.
 * 다만 무엇이 지워졌는지 아무 데도 남지 않으면 나중에 "이 업무는 왜 점검 항목이 셋뿐이었나"에
 * 답할 수 없다. sub_work_stts_hstry가 상태에 대해 하는 일을 체크리스트에 대해 한다.
 *
 * 표기는 sub_work_stts_hstry를 그대로 따랐다 — 전/후 값에 bfr_·aftr_ 접두사, 수행자는
 * prfmr_id, 시각은 chg_dt다. 두 이력을 나란히 읽는 자리가 생기므로 어휘가 갈리면 안 된다.
 *
 * **sub_work_chck_list_id에 FK를 걸지 않는다.** 삭제를 하드로 하기로 했기 때문이다(소프트
 * 삭제는 sub_work_chck_list를 읽는 세 자리 — 목록·미완료 개수·진행률 집계 — 에 필터를 하나씩
 * 더 요구하고, 그중 하나만 빠뜨리면 지워진 항목이 영영 완료를 막는다). 이력이 따로 남으므로
 * 하드로 지워도 흔적이 사라지지 않으며, 그 대신 이 컬럼은 이미 없는 행을 가리킬 수 있다.
 *
 * 변경 메서드를 열지 않는다 — 이력은 수정·삭제하지 않는다 (POL-004·BR-O11·AP-09·LG-13).
 * chg_dt를 DB 기본값이 아니라 Clock으로 채우는 이유도 sub_work_stts_hstry와 같다.
 */
@Entity
@Table(
        name = "sub_work_chck_list_hstry",
        indexes = {
            @Index(name = "idx_sub_work_chck_list_hstry_sub_work_id", columnList = "sub_work_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SubWorkChecklistHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sub_work_chck_list_hstry_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sub_work_id", nullable = false, updatable = false)
    private SubWorkEntity subWork;

    // 대상 항목의 식별자. 삭제된 항목도 가리키므로 FK가 아니다 (클래스 주석)
    @Column(name = "sub_work_chck_list_id", nullable = false, updatable = false)
    private Long checklistItemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "chck_chg_se_cd", nullable = false, length = 20, updatable = false)
    private ChecklistChangeType changeType;

    // 추가는 이전 문구가 없다
    @Column(name = "bfr_chck_artcl_cn", columnDefinition = "TEXT", updatable = false)
    private String previousArticle;

    // 삭제는 이후 문구가 없다. 지워진 항목의 마지막 모습은 previousArticle에 남는다
    @Column(name = "aftr_chck_artcl_cn", columnDefinition = "TEXT", updatable = false)
    private String nextArticle;

    /*
     * 변경을 수행한 회원. 조회·매핑 전용 연관이며 회원의 상태를 여기서 바꾸지 않는다
     * (개발지침서 DB-10·AR-07). sub_work_stts_hstry.prfmr_id와 같은 뜻이다.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "prfmr_id", nullable = false, updatable = false)
    private MemberEntity performer;

    @Column(name = "chg_dt", nullable = false, updatable = false)
    private Instant changedAt;

    public static SubWorkChecklistHistoryEntity added(
            SubWorkChecklistItemEntity item, MemberEntity performer, Instant changedAt) {
        return new SubWorkChecklistHistoryEntity(
                null,
                item.getSubWork(),
                item.getId(),
                ChecklistChangeType.ADDED,
                null,
                item.getArticle(),
                performer,
                changedAt);
    }

    public static SubWorkChecklistHistoryEntity modified(
            SubWorkChecklistItemEntity item,
            String previousArticle,
            MemberEntity performer,
            Instant changedAt) {
        return new SubWorkChecklistHistoryEntity(
                null,
                item.getSubWork(),
                item.getId(),
                ChecklistChangeType.MODIFIED,
                previousArticle,
                item.getArticle(),
                performer,
                changedAt);
    }

    /*
     * 삭제 이력은 **행을 지우기 전에** 만들어야 한다 — 지운 뒤에는 문구도 식별자도 읽을 수 없다.
     */
    public static SubWorkChecklistHistoryEntity removed(
            SubWorkChecklistItemEntity item, MemberEntity performer, Instant changedAt) {
        return new SubWorkChecklistHistoryEntity(
                null,
                item.getSubWork(),
                item.getId(),
                ChecklistChangeType.REMOVED,
                item.getArticle(),
                null,
                performer,
                changedAt);
    }
}
