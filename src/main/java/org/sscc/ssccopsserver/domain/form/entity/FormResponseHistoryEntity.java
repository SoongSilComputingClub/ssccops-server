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

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
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
                null, form, member, ResponseStatus.SUBMITTED, content, submittedAt, null, null);
    }

    /*
     * 작성 중인 내용 갱신 (#36). 이미 제출·심사된 응답은 여기서 막지 않고 호출부가 막는다 —
     * 무엇을 막을지는 응답 상태 변경 규칙(#37)이 정해질 때 함께 정해야 한다.
     */
    public void updateContent(ResponseContent content) {
        this.content = content;
    }

    /** 제출 (#35). 상태와 제출 일시는 항상 함께 움직인다 */
    public void submit(ResponseContent content, Instant submittedAt) {
        this.content = content;
        this.status = ResponseStatus.SUBMITTED;
        this.submittedAt = submittedAt;
    }

    /*
     * 심사 결과 반영 (#37). 전이 규칙을 서비스가 아니라 여기에 두는 것은 FormEntity.changeStatus와
     * 같은 이유다 — 상태를 바꾸는 경로가 늘어날 때 규칙이 호출부마다 복제되면 갈린다 (LY-02).
     *
     * 규칙은 "DRAFT가 얽히면 안 된다" 하나다. SUBMITTED·ACCEPTED·REJECTED 사이는 전부 열어 둔다
     * (심사 번복이 실제 운영에서 일어난다). 같은 상태로의 재지정도 막지 않는다 — 웹의 상태 변경
     * 시트는 현재 값을 고른 채로도 저장을 누를 수 있고, 그 요청은 아무것도 바꾸지 않을 뿐 잘못된
     * 요청이 아니다. 폼 상태(OPEN → OPEN을 막는다)와 갈리는 것은 그쪽이 '접수를 연다'는 사건인
     * 반면 이쪽은 '심사 결과'라는 값이기 때문이다.
     *
     * DRAFT → SUBMITTED가 여기서도 막히는 것이 요점이다. 제출은 응답자만 할 수 있는 일이며
     * 그 자리는 submit() 하나뿐이다 — 운영자가 상태만 SUBMITTED로 올려 두면 응답자가 낸 적 없는
     * 응답에 sbmsn_dt가 NULL인 채로 심사가 시작된다.
     *
     * **누가 이 변경을 했는지는 남지 않는다.** 데이터사전에 응답 상태 이력 테이블이 없어
     * mdfcn_dt만 갱신되며, 수행자 기록은 감사 로그(#8)가 확정되면 그쪽에 얹는다. 이 이슈에서
     * 이력 테이블을 새로 만들지 않기로 한 결정이다 (폼 상태 전이 #33과 같다).
     */
    public void changeStatus(ResponseStatus next) {
        if (this.status == ResponseStatus.DRAFT || next == ResponseStatus.DRAFT) {
            throw new GeneralException(FormErrorCode.INVALID_RESPONSE_STATUS_TRANSITION);
        }
        this.status = next;
    }
}
