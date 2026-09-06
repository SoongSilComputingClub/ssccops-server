package org.sscc.ssccopsserver.domain.share.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import org.sscc.ssccopsserver.domain.share.code.ShareTargetType;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * shr_lnk(공유_링크) — 운영 콘텐츠를 메신저에 붙일 수 있게 만든 토큰 (ssccops#200 · ADR-0016).
 *
 * **토큰이 주는 것은 미리보기까지다.** 크롤러는 이 토큰으로 제목·요약을 받아 카드를 만들지만,
 * 사람이 링크를 누르면 종전대로 로그인과 권한 검사를 거쳐야 내용을 본다. 열람까지 여는 안을
 * 기각한 근거는 ADR-0016에 있다 — 요구는 "열지 않고도 무엇인지 안다"였지 "권한 없는 사람도
 * 내용을 본다"가 아니었고, 후자는 인가 체계 밖에 읽기 경로를 하나 만든다.
 *
 * **토큰을 저장하는 이유는 거둘 수 있어야 하기 때문이다.** 서명만으로 만들면(stateless) 테이블이
 * 없어 간단하지만 한 번 나간 링크를 되돌릴 방법이 없고, 만료도 두지 않기로 했으므로 영영
 * 살아 있다(ADR-0016).
 *
 * **만료를 두지 않는다.** 메신저는 OG를 캐싱해 카드가 굳는데 링크만 죽으면 멀쩡해 보이는 카드를
 * 눌렀을 때 404가 된다 — 공유한 사람은 자기 링크가 죽은 줄 모르고 받은 사람은 시스템이 고장 난
 * 줄 안다. 대신 운영자가 명시적으로 "공유 중지"를 누르는 폐기만 둔다(의도된 행동이라 그 결과도
 * 의도된 것이다).
 *
 * 대상을 (`trgt_se_cd`, `trgt_id`) 두 값으로 둔 근거와 FK를 걸지 않는 근거는
 * {@link ShareTargetType} 주석에 있다.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "shr_lnk",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_shr_lnk_tkn",
                        columnNames = {"shr_tkn"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ShareLinkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "shr_lnk_id")
    private Long id;

    /*
     * 링크에 박히는 무작위 토큰. **이 값이 이 방식의 요점이다** — 공개 메타 API를 기각하고
     * 토큰으로 간 이유가 식별자를 훑는 공격면을 없애는 것이었다(ADR-0016). 연속 정수를 쓰면
     * 1부터 훑는 것만으로 동아리 업무 제목이 전부 수집된다.
     *
     * updatable = false로 잠근다 — 토큰을 바꾸는 것은 이 행을 고치는 일이 아니라 폐기하고 새로
     * 발급하는 일이다.
     */
    @Column(name = "shr_tkn", nullable = false, length = 64, updatable = false)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(name = "trgt_se_cd", nullable = false, length = 20, updatable = false)
    private ShareTargetType targetType;

    @Column(name = "trgt_id", nullable = false, updatable = false)
    private Long targetId;

    /*
     * 발급한 회원. **누가 언제 무엇을 공유했는지 남기는 것**이 테이블에 저장하기로 한 이유의
     * 절반이다(나머지 절반이 폐기 가능성이다 — ADR-0016).
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "iss_mbr_id", nullable = false, updatable = false)
    private MemberEntity issuer;

    /*
     * 폐기 일시. NULL이면 살아 있다.
     *
     * 별도의 `rvk_yn`을 두지 않는 것은 같은 사실이 두 벌이 되기 때문이다 — 둘을 두면 언젠가
     * 플래그만 켜지고 일시가 비거나 그 반대인 행이 생기고, 그때 무엇이 참인지 알 수 없다.
     * 운영 도메인의 소프트 삭제(`OperationEntity.deletedAt`)가 같은 모양이다.
     */
    @Column(name = "rvk_dt")
    private Instant revokedAt;

    @CreatedDate
    @Column(name = "crt_dt", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "mdfcn_dt", nullable = false)
    private Instant updatedAt;

    public static ShareLinkEntity issue(
            String token, ShareTargetType targetType, Long targetId, MemberEntity issuer) {
        return new ShareLinkEntity(null, token, targetType, targetId, issuer, null, null, null);
    }

    /** 살아 있는 링크인가. 미리보기를 내줄지의 판정은 언제나 이 하나를 지난다 */
    public boolean isActive() {
        return revokedAt == null;
    }

    /*
     * 폐기. **이미 폐기된 링크를 다시 폐기해도 거절하지 않는다** — 운영자가 "공유 중지"를 두 번
     * 눌렀을 때 두 번째가 오류로 보일 이유가 없고, 결과(그 링크로는 아무것도 열리지 않는다)가
     * 같다. 처음 폐기한 시각을 덮어쓰지 않는 것은 그 값이 이력이기 때문이다.
     */
    public void revoke(Instant now) {
        if (revokedAt == null) {
            this.revokedAt = now;
        }
    }
}
