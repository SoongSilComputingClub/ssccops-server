package org.sscc.ssccopsserver.domain.form.entity;

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

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * form_qitem_hstry(폼_문항구성_이력) — 문항 구성이 바뀔 때마다 그 시점의 구성을 통째로 남긴다 (#140).
 *
 * form_rspns_hstry와 달리 **여기는 진짜로 행이 쌓인다.** 응답 이력은 (form_id, mbr_id) UNIQUE로
 * 한 회원당 한 행을 유지하며 내용만 갈리지만, 이쪽은 버전마다 한 행이라 과거 구성이 남는다 —
 * 이 테이블의 존재 이유가 "무엇이 언제 바뀌었는가"이므로 덮어쓰면 뜻이 없다.
 *
 * 그래서 모든 컬럼이 updatable = false다. 이미 쓴 이력을 고칠 수 있으면 증거가 되지 못한다
 * (mbr_grd_hstry·mbr_stts_hstry와 같은 판단, #78).
 *
 * (form_id, qitem_ver) UNIQUE는 같은 버전이 두 번 기록되는 것을 막는다. 버전을 올리는 자리가
 * FormEntity.update 하나뿐이라 애플리케이션 코드로는 도달하지 않지만, 같은 폼을 두 요청이 동시에
 * 저장하면 둘 다 같은 값을 읽고 같은 번호로 올릴 수 있다. 그 경우 어느 쪽이 실제로 저장됐는지
 * 알 수 없는 이력 두 줄이 남는데, 그것은 이력이 없는 것보다 나쁘다.
 *
 * **정규화하지 않고 구성 전체(JSONB)를 통째로 복사한다.** 문항 단위로 diff를 남기는 편이
 * 저장 공간에는 유리하지만, 문항은 순서·페이지·분기까지 서로 얽혀 있어 diff를 되짚어 그 시점의
 * 폼을 재구성하려면 결국 diff 적용기를 하나 더 만들어야 한다 — form.qitem_cpst_cn을 통째로 둔
 * 것과 같은 이유다(QuestionCompositionContent 주석).
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "form_qitem_hstry",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_form_qitem_hstry_form_version",
                        columnNames = {"form_id", "qitem_ver"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class FormQuestionHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "form_qitem_hstry_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "form_id", nullable = false, updatable = false)
    private FormEntity form;

    @Column(name = "qitem_ver", nullable = false, updatable = false)
    private Integer questionVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "qitem_cpst_cn", nullable = false, updatable = false)
    private QuestionCompositionContent questionComposition;

    /*
     * 변경자. **요청 본문이 아니라 @CurrentMember에서 온다** (#78이 세운 규칙과 같다) — 받아 주면
     * "누가 바꿨는가"를 스스로 적어 넣을 수 있어 이력이 증거가 되지 못한다.
     *
     * nullable인 것은 회원이 지워진 뒤에도 이력 행은 남아야 하고, 사람이 아닌 경로(시드·이관
     * 스크립트)가 구성을 세우는 경우가 있기 때문이다 (mbr_grd_hstry.chnrg_mbr_id와 같다).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chnrg_mbr_id", updatable = false)
    private MemberEntity changedBy;

    @CreatedDate
    @Column(name = "crt_dt", nullable = false, updatable = false)
    private Instant createdAt;

    /*
     * 이력 한 행. 버전과 구성을 인자로 받지 않고 폼에서 직접 읽는 것이 요점이다 — 호출부가
     * 따로 넘기게 두면 "버전은 3인데 내용은 2일 때의 것"인 행을 만들 수 있고, 그런 이력은
     * 없는 것보다 나쁘다. 부르는 쪽은 폼을 먼저 바꾸고 이 팩토리를 부르기만 한다.
     *
     * 구성을 깊은 복사하는 것은 폼이 나중에 다시 수정될 때를 위해서다. 같은 트랜잭션 안에서
     * 폼을 한 번 더 저장하면 form.questionComposition은 새 객체로 교체되므로 지금은 참조를
     * 공유해도 문제가 없지만, 그 사실에 기대면 언젠가 구성을 제자리에서 고치는 코드가 생기는
     * 순간 이력이 조용히 최신 내용으로 바뀐다.
     */
    public static FormQuestionHistoryEntity of(FormEntity form, MemberEntity changedBy) {
        return new FormQuestionHistoryEntity(
                null,
                form,
                form.getQuestionVersion(),
                form.getQuestionComposition().deepCopy(),
                changedBy,
                null);
    }
}
