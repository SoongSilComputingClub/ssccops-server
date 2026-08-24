package org.sscc.ssccopsserver.domain.form.entity;

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

import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.sscc.ssccopsserver.domain.form.code.ResponseReviewAction;
import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.code.error.FormErrorCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * form_rspns_hstry(폼_응답_이력) — 한 회원이 한 폼에 낸 응답.
 *
 * '이력' 테이블이지만 제출할 때마다 행이 쌓이지 않는다. (form_id, mbr_id) UNIQUE로 한 회원당
 * 한 행을 유지하고 그 행의 내용·상태만 바뀐다. 이 제약은 응답 자동 저장(#36)이 성립하기 위한
 * 전제다 — 자동 저장은 "지금 작성 중인 그 응답"을 매번 같은 자리에서 찾아야 하는데, 행이
 * 여러 개일 수 있으면 어느 것이 최신인지 판정하는 규칙이 하나 더 필요해진다.
 * 공개 폼 응답 제출(#35)의 중복 제출 방지도 같은 제약에 얹힌다. 선조회만으로는 동시 제출을
 * 막지 못하므로 DB에 둔다.
 *
 * mbr_id는 NOT NULL이다 (ssccops #61). 공개 폼 접속자도 Google OAuth 로그인과 회원가입을
 * 먼저 거치므로 비회원 응답이 존재하지 않는다. 웹 타입(entities/response)의 @db-pending
 * 주석은 이 결정으로 해소된다 — nullable 전환도, 비회원 응답 테이블 분리도 하지 않는다.
 *
 * sbmsn_dt(제출 일시)는 nullable이다 (ssccops #64). 상태 어휘에 DRAFT가 들어오면서
 * "아직 제출하지 않은 응답"이 정상 상태가 됐고, 그 행은 제출 일시를 가질 수 없다.
 * 두 사실은 같이 움직인다 — 어느 한쪽만 되돌리면 데이터가 거짓말을 하게 된다.
 *
 * sbmsn_seq(제출 회차)는 #141에서 더했다. 수정요청을 받은 응답은 같은 행이 여러 번 제출되는데,
 * 행이 하나뿐이라 "몇 번째 제출인가"를 담을 자리가 여기 말고 없다. 이 값이 있어야
 * form_rspns_rvw_hstry의 이력 행이 어느 제출에 대한 처리였는지 가리킬 수 있다 — 없으면
 * 타임라인은 "승인 → 수정요청 → 승인"처럼 처리만 나열될 뿐 그 사이에 응답 내용이 바뀌었다는
 * 사실이 사라진다.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "form_rspns_hstry",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_form_rspns_hstry_form_member",
                        columnNames = {"form_id", "mbr_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class FormResponseHistoryEntity {

    /** 최초 제출 회차 (#141). 아직 내지 않은 DRAFT도 이 값에서 시작한다 */
    private static final int FIRST_SUBMISSION = 1;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "form_rspns_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "form_id", nullable = false, updatable = false)
    private FormEntity form;

    /*
     * 응답자(mbr.mbr_id). 응답의 주인은 바뀌지 않으므로 updatable = false로 잠근다.
     * 조회·매핑 전용 연관이며 회원의 상태를 여기서 바꾸지 않는다.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mbr_id", nullable = false, updatable = false)
    private MemberEntity member;

    @Enumerated(EnumType.STRING)
    @Column(name = "rspns_stts_cd", nullable = false, length = 20)
    private ResponseStatus status;

    /*
     * 응답 내용(JSONB). 문항 구성과 같은 방식으로 매핑한다 — 자세한 근거는 FormEntity와
     * ResponseContent 주석 참조.
     *
     * DRAFT 상태에서도 NOT NULL이다. 아직 아무것도 입력하지 않았다면 NULL이 아니라 빈 객체({})가
     * 들어간다 — "답이 없다"와 "행이 깨졌다"를 같은 값으로 표현하지 않기 위해서다.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rspns_cn", nullable = false)
    private ResponseContent content;

    /** 최종 제출 일시. DRAFT인 동안에는 NULL이다 (ssccops #64) */
    @Column(name = "sbmsn_dt")
    private Instant submittedAt;

    /*
     * 이 답이 어느 문항 구성에 대한 답인가 (#140 · form.qitem_ver의 사본).
     *
     * 폼의 현재 버전을 나중에 다시 읽으면 되지 않는다 — 그 값은 이미 다음 버전일 수 있고,
     * 그러면 "지원자가 무엇을 보고 답했는가"에 답할 수 없다. 이력(form_qitem_hstry)과 짝을
     * 이루어야 비로소 그 시점의 폼을 되짚을 수 있다.
     *
     * 옛 버전 구성으로 응답을 다시 렌더하는 것은 이번 범위가 아니다. 응답 표시가 qitemId
     * 기준이라 대체로 동작하고, 필요해지면 이 값과 이력만으로 열 수 있다.
     *
     * @ColumnDefault는 FormEntity의 sys_yn·qitem_ver와 같은 이유다 — 이미 행이 있는 dev·prod에
     * DEFAULT 없는 NOT NULL 컬럼을 붙이면 ALTER가 실패한다.
     */
    @ColumnDefault("1")
    @Column(name = "qitem_ver", nullable = false)
    private Integer questionVersion;

    /*
     * 제출 회차 (#141). 최초 제출이 1이고 수정요청 뒤 재제출할 때마다 1씩 는다.
     *
     * 아직 내지 않은 DRAFT도 1이다 — 0으로 두면 "몇 번 냈는가"와 "지금 몇 회차를 쓰고 있는가"를
     * 한 컬럼이 겸하게 되어, 제출 시점에 0 → 1로 올리는 분기가 하나 더 생긴다.
     *
     * columnDefinition에 default를 적는 것은 ddl-auto: update로 이 컬럼이 붙는 dev·prod 때문이다.
     * 이미 행이 있는 테이블에 기본값 없는 NOT NULL 컬럼을 붙이면 ALTER 자체가 실패하고,
     * Hibernate는 그 실패를 로그만 남기고 넘어가 컬럼 없는 채로 애플리케이션이 뜬다.
     */
    @Column(name = "sbmsn_seq", nullable = false, columnDefinition = "integer default 1")
    private int submissionSequence;

    @CreatedDate
    @Column(name = "crt_dt", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "mdfcn_dt", nullable = false)
    private Instant updatedAt;

    /*
     * 임시저장 응답 생성 (#36). 제출 일시는 NULL로 서버가 고정하며 클라이언트가 지정할 수 없다.
     * 내용이 비어 있어도 행을 만든다 — 자동 저장은 첫 타이핑 시점에 이미 저장할 자리가 있어야 한다.
     */
    public static FormResponseHistoryEntity createDraft(
            FormEntity form, MemberEntity member, ResponseContent content) {
        return new FormResponseHistoryEntity(
                null,
                form,
                member,
                ResponseStatus.DRAFT,
                content == null ? ResponseContent.of(null) : content,
                null,
                form.getQuestionVersion(),
                FIRST_SUBMISSION,
                null,
                null);
    }

    /*
     * 임시저장을 거치지 않고 바로 제출된 응답 (#35). 제출 일시는 주입된 Clock에서 온 값을
     * 받는다 — 엔티티가 Instant.now()를 직접 부르면 테스트가 시간을 고정할 수 없다.
     */
    public static FormResponseHistoryEntity createSubmitted(
            FormEntity form, MemberEntity member, ResponseContent content, Instant submittedAt) {
        return new FormResponseHistoryEntity(
                null,
                form,
                member,
                ResponseStatus.SUBMITTED,
                content,
                submittedAt,
                form.getQuestionVersion(),
                FIRST_SUBMISSION,
                null,
                null);
    }

    /*
     * 작성 중인 내용 갱신 (#36). 이미 제출·심사된 응답은 여기서 막지 않고 호출부가 막는다 —
     * 무엇을 막을지는 응답 상태 변경 규칙(#37)이 정해질 때 함께 정해야 한다.
     */
    public void updateContent(ResponseContent content) {
        this.content = content;
        stampQuestionVersion();
    }

    /*
     * 제출 (#35 · 재제출 #141). 상태와 제출 일시는 항상 함께 움직인다.
     *
     * **"지금 낼 수 있는 응답인가"의 판정이 여기 하나로 모였다.** 원래는 서비스가 먼저
     * `status != DRAFT면 409`로 걸렀는데, #141에서 수정요청 응답의 재제출이 열리면서 그 판정이
     * 상태별로 갈라졌다 — 서비스에 그대로 두면 상태 어휘가 늘 때마다 규칙이 호출부마다 복제된다
     * (LY-02 · changeStatus를 여기 둔 것과 같은 이유).
     *
     *   DRAFT             → 최초 제출. 회차는 1 그대로다
     *   CHANGES_REQUESTED → 재제출. SUBMITTED로 돌아가고 회차가 1 는다
     *   REJECTED          → 409 RESPONSE_ALREADY_REJECTED. 반려는 응답자에게 종결이다
     *   그 밖(SUBMITTED · ACCEPTED) → 409 RESPONSE_ALREADY_SUBMITTED
     *
     * 반려를 따로 끊는 것은 응답자가 할 수 있는 일이 다르기 때문이다 — "이미 제출했다"는
     * 기다리라는 뜻이지만 반려는 그 응답에 대해 끝났다는 뜻이다. 검토자도 그 상태를 되돌릴 수
     * 없으므로(changeStatus) 반려는 양쪽 모두에게 종결이며, 다시 낼 길은 새 응답뿐이다.
     *
     * 회차를 올리는 자리도 여기 하나뿐이다. 서비스가 올리면 이력에 적히는 회차와 응답 행의
     * 회차가 갈릴 수 있고, 그 어긋남은 타임라인이 이미 굳은 뒤에야 드러난다.
     */
    public void submit(ResponseContent content, Instant submittedAt) {
        if (this.status == ResponseStatus.REJECTED) {
            throw new GeneralException(FormErrorCode.RESPONSE_ALREADY_REJECTED);
        }
        if (this.status != ResponseStatus.DRAFT
                && this.status != ResponseStatus.CHANGES_REQUESTED) {
            throw new GeneralException(FormErrorCode.RESPONSE_ALREADY_SUBMITTED);
        }
        if (this.status == ResponseStatus.CHANGES_REQUESTED) {
            this.submissionSequence += 1;
        }
        this.content = content;
        this.status = ResponseStatus.SUBMITTED;
        this.submittedAt = submittedAt;
        stampQuestionVersion();
    }

    /*
     * 답이 갱신될 때마다 그 시점의 문항 구성 버전을 다시 찍는다 (#140).
     *
     * 처음 만들 때만 찍으면 자동 저장으로 며칠에 걸쳐 쓴 응답은 첫 타이핑 시점의 버전을 달고
     * 제출되는데, 그 사이 폼이 바뀌었다면 실제로 답한 구성과 어긋난다 — 마지막에 쓴 답이
     * 마지막에 본 구성에 대한 답이다.
     *
     * 호출부에서 버전을 넘기지 않고 폼에서 직접 읽는 것은, 내용과 버전이 갈릴 자리를 만들지
     * 않기 위해서다 (FormQuestionHistoryEntity.of와 같은 판단). 심사(changeStatus)에서는
     * 찍지 않는다 — 답이 바뀌지 않는 조작이라 다시 찍으면 응답자가 보지도 않은 구성이 기록된다.
     */
    private void stampQuestionVersion() {
        this.questionVersion = form.getQuestionVersion();
    }

    /*
     * 심사 결과 반영 (#37 · 전이표 개정 #141). 전이 규칙을 서비스가 아니라 여기에 두는 것은
     * FormEntity.changeStatus와 같은 이유다 — 상태를 바꾸는 경로가 늘어날 때 규칙이 호출부마다
     * 복제되면 갈린다 (LY-02).
     *
     *   현재 \ 대상        | SUBMITTED | CHANGES_REQUESTED | ACCEPTED | REJECTED
     *   SUBMITTED         | ✕(같은 값) | →                 | →        | →
     *   CHANGES_REQUESTED | ✕(재제출만) | ✕(같은 값)         | →        | →
     *   ACCEPTED          | ✕         | ✕                 | ✕        | ✕
     *   REJECTED          | ✕         | ✕                 | ✕        | ✕
     *   DRAFT가 얽히는 모든 칸 ✕
     *
     * ── 심사 번복을 없앴다 (#141) ────────────────────────────
     * #37에서는 SUBMITTED·ACCEPTED·REJECTED 사이가 전부 열려 있었다. 닫은 이유는 **승인 직후
     * 후속 처리가 시작되기 때문**이다 — 기획안이 승인되면 활동이 개설되고 역할이 부여된다.
     * 그 뒤에 승인을 반려로 되돌리면 이미 만들어진 것들을 되돌릴 방법이 없고, 회차가 하나라도
     * 기록된 뒤에는 응답 자체도 되돌릴 수 없다. 종결을 '응답자가 다시 낼 수 없다'로만 좁혀 두면
     * 검토자 쪽에 그 구멍이 그대로 남는다.
     *
     * 오조작의 탈출구는 번복이 아니라 **새 응답**이다 — 다중 응답을 허용하는 폼에서는 응답자가
     * 다시 낼 수 있고, 그렇지 않은 폼에서는 운영자가 데이터를 직접 고쳐야 하는 예외 상황이다.
     * 아직 끝나지 않은 심사(SUBMITTED · CHANGES_REQUESTED)에서는 결론을 자유롭게 고를 수
     * 있으므로, 되돌릴 수 없게 되는 것은 결론을 낸 뒤부터다.
     *
     * ── 같은 상태로의 재지정도 막는다 ──────────────────────────
     * #37에서는 "아무것도 바꾸지 않을 뿐 잘못된 요청은 아니다"라며 통과시켰고, 폼 상태(OPEN →
     * OPEN을 막는다)와 갈리는 근거는 그쪽이 '사건'이고 이쪽은 '값'이라는 것이었다. 이력이 생긴
     * 지금 그 대비는 성립하지 않는다 — 검토도 처리 이력 한 줄을 남기는 '사건'이 됐고, 통과시키면
     * 아무것도 바꾸지 않은 처리가 타임라인에 쌓여 실제 심사 시점을 못 찾게 된다 (등급·상태 변경의
     * NO_CHANGE #78과 같은 이유).
     *
     * ── SUBMITTED로 가는 길은 재제출뿐이다 ───────────────────
     * DRAFT → SUBMITTED가 막히는 것과 같은 이유가 CHANGES_REQUESTED → SUBMITTED에도 걸린다.
     * 제출은 응답자만 할 수 있는 일이며 그 자리는 submit() 하나다 — 검토자가 상태만 되돌려
     * 놓으면 응답 내용은 그대로인데 회차만 오르지 않은 '새 제출'이 생긴다.
     *
     * **누가 이 변경을 했는지는 review()가 남긴다** (#141). 이 메서드는 여전히 전이만 판정하며
     * 수행자를 모른다 — 상태만 바꾸는 경로(예: 테스트 표본 준비)가 남아 있고, 그 경로까지
     * 이력을 강제하면 이력 없는 전이를 만들 수 없게 되는 대신 이력의 뜻이 흐려진다. 대신
     * 운영 경로에서 상태를 바꾸는 유일한 입구는 review()이고, 그쪽이 이력 기록과 한 몸이다.
     */
    public void changeStatus(ResponseStatus next) {
        if (this.status == ResponseStatus.DRAFT || next == ResponseStatus.DRAFT) {
            throw new GeneralException(FormErrorCode.INVALID_RESPONSE_STATUS_TRANSITION);
        }
        if (this.status == ResponseStatus.ACCEPTED || this.status == ResponseStatus.REJECTED) {
            throw new GeneralException(FormErrorCode.INVALID_RESPONSE_STATUS_TRANSITION);
        }
        if (next == ResponseStatus.SUBMITTED || this.status == next) {
            throw new GeneralException(FormErrorCode.INVALID_RESPONSE_STATUS_TRANSITION);
        }
        this.status = next;
    }

    /*
     * 검토 처리 (#141 · POST /v1/forms/{formId}/responses/{formRspnsId}/reviews).
     *
     * 목표 상태를 처리 구분으로 옮기고 전이를 적용한 뒤, 이력 행이 적을 처리 구분을 돌려준다.
     * 서비스가 아니라 여기서 옮기는 것은 두 판정이 한 번에 성립해야 하기 때문이다 — "검토로
     * 도달할 수 있는 상태인가"와 "그 전이가 허용되는가"를 나눠 두면 한쪽만 통과한 요청이
     * 상태는 바꾸고 이력은 남기지 못하는 자리를 만든다.
     *
     * SUBMITTED로 되돌리는 요청은 두 겹으로 끊긴다 — 처리 구분에 검토자가 쓸 SUBMIT이 없고
     * (ResponseReviewAction), 전이표에도 그 칸이 없다(changeStatus). 같은 결론을 두 곳이 갖는
     * 것은 묻는 것이 다르기 때문이다: 앞은 "이력에 뭐라고 적을 것인가"이고 뒤는 "이 응답이
     * 그리로 갈 수 있는가"다. 어느 한쪽만 있어도 막히지만, 둘 중 하나를 지우면 다른 하나가
     * 왜 그 자리에 있는지 알 수 없어진다.
     */
    public ResponseReviewAction review(ResponseStatus targetStatus) {
        ResponseReviewAction action =
                ResponseReviewAction.reviewTargetOf(targetStatus)
                        .orElseThrow(
                                () ->
                                        new GeneralException(
                                                FormErrorCode.INVALID_RESPONSE_STATUS_TRANSITION));
        changeStatus(action.resultStatus());
        return action;
    }
}
