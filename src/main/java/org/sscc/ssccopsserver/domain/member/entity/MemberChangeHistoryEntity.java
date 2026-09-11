package org.sscc.ssccopsserver.domain.member.entity;

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

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.sscc.ssccopsserver.domain.member.code.MemberChangeField;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * 회원 정보 변경 이력 (mbr_chg_hstry · #226 · 등재 ssccops#162).
 *
 * ── 왜 범용 한 표인가 ──────────────────────────────────────────
 * 학번을 고칠 수 있게 하려면(ssccops#161) 그 대가로 "누가 언제 무엇을 무엇으로" 바꿨는지가
 * 남아야 한다. 학번만을 위한 전용 이력을 만들지 않은 것은 다음에 "학과가 바뀌었다"·"연락처가
 * 바뀌었다"에서 같은 논의를 반복하고 그때마다 테이블이 늘기 때문이다 — 항목은 chg_artcl_cd
 * 한 컬럼이 가른다(MemberChangeField).
 *
 * **등급·상태는 옮겨 오지 않는다.** 그쪽은 적용일·사유·종료 예정일처럼 이 표가 담을 수 없는
 * 값을 갖고 조회도 도메인 사건으로 다룬다. 옮기면 같은 변경이 두 표에 남는다.
 *
 * ── 값이 문자열인 이유 ────────────────────────────────────────
 * bfr_cn·aftr_cn은 항목마다 타입이 다른 값(학번은 문자열, 기수·학년은 숫자)을 한 컬럼에
 * 담으므로 문자열이다. 타입별 컬럼을 두면 항목이 늘 때마다 컬럼이 늘고, 그것은 전용 테이블로
 * 돌아가는 길이다. **비교·집계 대상이 아니라 사람이 읽는 기록**이라 문자열로 충분하다
 * (ssccops#162 설계 노트).
 *
 * 비어 있던 값·지운 값은 빈 문자열이 아니라 NULL이다. "빈 문자열로 바꿨다"와 "지웠다"가 같은
 * 행으로 보이면 학번의 NULL 저장 규칙(빈 문자열이면 UNIQUE 충돌)을 이력이 뒤집어 말하게 된다.
 *
 * ── 전 컬럼이 updatable = false다 (POL-004) ───────────────────
 * 등급·상태 이력과 같은 잠금이다. 이력 행은 고쳐 쓰는 값이 아니라 그때 무슨 일이 있었는가의
 * 기록이므로, 나중에 값을 갈아 끼울 수 있으면 이 표는 근거로 쓸 수 없다. 적용일·사유 컬럼을
 * 두지 않은 것도 같은 맥락이다 — 이름·연락처는 고친 순간이 곧 적용이고 사유를 물을 자리도 없다.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "mbr_chg_hstry")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class MemberChangeHistoryEntity {

    /** 이전·이후 내용의 길이 상한. 데이터사전의 도메인 내용V500 그대로다 */
    public static final int CONTENT_MAX_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "mbr_chg_hstry_id")
    private Long id;

    // 회원 본인 데이터 — 회원이 지워지면 함께 지워진다 (V9 · ADR-0021). chnrg_mbr_id는 행위자
    // 참조라 cascade가 없다 — 본인 수정이면 변경자가 본인이지만 그 행은 mbr_id 쪽으로 먼저 지워진다.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "mbr_id", nullable = false, updatable = false)
    private MemberEntity member;

    /*
     * 어느 항목이 바뀌었는가. 기준 코드 테이블이 아니라 고정 어휘라 enum을 문자열로 담는다 —
     * 근거는 MemberChangeField 주석에 있다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "chg_artcl_cd", nullable = false, updatable = false, length = 20)
    private MemberChangeField changeField;

    @Column(name = "bfr_cn", updatable = false, length = CONTENT_MAX_LENGTH)
    private String previousContent;

    @Column(name = "aftr_cn", updatable = false, length = CONTENT_MAX_LENGTH)
    private String newContent;

    /*
     * 변경자 (chnrg_mbr_id). **인증 주체에서 오며 요청 본문으로 받지 않는다** (#78 규칙) —
     * 받아 주면 스스로 적어 넣을 수 있어 이력이 증거가 되지 못한다.
     *
     * 등급·상태 이력과 달리 NOT NULL이다. 그쪽에는 배치·이관으로 생겨 사람이 없는 행이 있지만
     * 이 표에 행을 만드는 경로는 회원 정보 수정 두 곳뿐이고 둘 다 @CurrentMember를 지나온다 —
     * 본인 수정이면 변경자가 본인일 뿐 없는 것이 아니다.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chnrg_mbr_id", nullable = false, updatable = false)
    private MemberEntity changedBy;

    @CreatedDate
    @Column(name = "crt_dt", updatable = false)
    private Instant createdAt;

    public static MemberChangeHistoryEntity create(
            MemberEntity member,
            MemberChangeField changeField,
            String previousContent,
            String newContent,
            MemberEntity changedBy) {
        return new MemberChangeHistoryEntity(
                null, member, changeField, previousContent, newContent, changedBy, null);
    }
}
