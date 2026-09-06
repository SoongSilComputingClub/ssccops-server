package org.sscc.ssccopsserver.domain.operation.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * oper_shr_lnk(운영_공유_링크) — 운영 건 하나에 발급된 공유 토큰 (ssccops#200 · ADR-0016).
 *
 * **FK가 oper_id 하나인 것이 이 테이블의 요점이다.** oper는 업무·하위 업무·회의의 공통
 * 부모라 세 종류를 한 FK로 덮는다. file_rfrnc가 trgt_se_cd + trgt_id 다형 키를 쓴 것은
 * 대상 테이블이 여럿이라 FK를 아예 걸 수 없었기 때문인데, 여기서는 걸 수 있다 — 걸 수 있으면
 * 거는 편이 낫다(고아 행이 생기지 않고 대상이 실재한다는 것을 DB가 보장한다).
 *
 * **토큰은 URL-safe 난수다.** 예측 가능하면 이 설계의 근거가 통째로 무너진다 — 공개 메타
 * API(/public/v1/sub-works/{id}/meta)를 기각한 이유가 "식별자가 연속 정수라 1부터 훑으면
 * 업무 제목이 전부 수집된다"였고, 토큰은 그 공격면 자체를 없애려고 있는 것이다.
 *
 * **폐기는 행을 지우지 않고 rvk_dt를 채운다** (ADR-0016이 저장을 택한 이유). 지우면 누가 언제
 * 무엇을 공유했는지가 함께 사라진다. 만료를 두지 않기로 했으므로 거두는 방법은 이 명시적
 * 폐기 하나뿐이고, 그건 사람이 의도한 행동이라 그 결과(카드를 눌러도 안 열림)도 의도된 것이다.
 *
 * **한 운영 건에 유효한 토큰은 최대 하나다.** 발급을 다시 부르면 있는 것을 그대로 돌려준다 —
 * 누를 때마다 쌓이면 무엇을 폐기해야 할지 알 수 없어 폐기가 의미를 잃는다. 폐기된 행은
 * 남으므로 운영 건 하나에 행은 여럿일 수 있고, 그래서 UNIQUE는 (oper_id)가 아니라 토큰에 건다.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "oper_shr_lnk",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_oper_shr_lnk_tkn",
                        columnNames = {"shr_tkn"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class OperationShareLinkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "oper_shr_lnk_id")
    private Long id;

    /** 공유 대상. oper 하나가 업무·하위 업무·회의를 모두 덮는다 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "oper_id", nullable = false, updatable = false)
    private OperationEntity operation;

    /*
     * 링크에 박히는 값. 발급 뒤 바뀌지 않는다(updatable = false) — 이미 나간 링크가 조용히
     * 죽는 경로를 만들지 않는다. 길이는 생성기(ShareTokenGenerator)가 내는 값에 맞춘다.
     */
    @Column(name = "shr_tkn", nullable = false, length = 64, updatable = false)
    private String token;

    /** 공유한 사람. 사후 변경 불가다 — "누가 공유했는가"가 이 행의 존재 이유 중 하나다 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creatr_mbr_id", nullable = false, updatable = false)
    private MemberEntity creator;

    /** 폐기 일시. NULL이면 유효하다 — 만료가 없으므로 이 값이 유일한 종료 조건이다 */
    @Column(name = "rvk_dt")
    private Instant revokedAt;

    @CreatedDate
    @Column(name = "crt_dt", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "mdfcn_dt", nullable = false)
    private Instant updatedAt;

    public static OperationShareLinkEntity issue(
            OperationEntity operation, String token, MemberEntity creator) {
        return new OperationShareLinkEntity(null, operation, token, creator, null, null, null);
    }

    /*
     * 공유 중지. 이미 폐기된 링크를 다시 폐기해도 처음 폐기한 시각을 덮어쓰지 않는다 —
     * 이력으로서의 값이 그 시각에 있다.
     */
    public void revoke(Instant revokedAt) {
        if (this.revokedAt == null) {
            this.revokedAt = revokedAt;
        }
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }
}
