package org.sscc.ssccopsserver.domain.operation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.operation.code.error.OperationErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * mtg(회의) — oper(운영)의 확장 테이블. 제목·기간·담당자는 부모 oper가 갖고, 여기에는
 * 회의 고유 속성만 둔다(work·sub_work와 같은 구조 — OperationEntity 클래스 주석 참고).
 *
 * mtg_id를 자체 PK로 가지며 oper_id는 일반 FK다. PK=FK 상속이 아니므로 @MapsId를 쓰지 않는다.
 * oper_id에 UNIQUE를 건다 — 회의는 자기 oper를 하나만 갖고 공유하지 않는다(work·sub_work와
 * 같은 1:1 관계이나, 그쪽은 컬렉션 쪽에서 UNIQUE를 걸 필요가 없어 명시하지 않았을 뿐이다).
 *
 * 회의 책임자(mtg_rbprsn_id)는 항상 oper.pic_id(담당자)와 같은 회원이다 — 등록 화면에
 * 별도 입력란을 두지 않기로 했다(ssccops-web#56). 그래도 컬럼을 따로 두는 것은 데이터사전이
 * 이미 그렇게 정의하고 있고, 프론트 응답도 mtg_rbprsn_id를 회의 확장 속성으로 그리기 때문이다.
 */
@Entity
@Table(
        name = "mtg",
        uniqueConstraints = @UniqueConstraint(name = "uk_mtg_oper_id", columnNames = "oper_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class MeetingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "mtg_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "oper_id", nullable = false)
    private OperationEntity operation;

    @Enumerated(EnumType.STRING)
    @Column(name = "mtg_se_cd", length = 20)
    private MeetingCategory meetingCategory;

    @Enumerated(EnumType.STRING)
    @Column(name = "atnd_trgt_cd", length = 20)
    private AttendeeScope attendeeScope;

    @Enumerated(EnumType.STRING)
    @Column(name = "mtg_stts_cd", length = 20)
    private MeetingStatus meetingStatus;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mtg_rbprsn_id", nullable = false)
    private MemberEntity responsiblePerson;

    @Column(name = "mtg_plc_nm", length = 100)
    private String location;

    // 내부 상세본
    @Column(name = "insd_mtg_dtl_cn", columnDefinition = "TEXT")
    private String internalDetail;

    // 제출 요약본
    @Column(name = "otsd_mtg_dtl_cn", columnDefinition = "TEXT")
    private String externalSummary;

    /*
     * 회의 등록(OPS-024)용 생성 팩토리. 상태는 항상 SCHEDULED(예정)로 서버가 고정하며
     * 클라이언트가 지정할 수 없다. 회의 책임자는 서비스가 oper.pic_id와 같은 회원을 넘긴다.
     */
    public static MeetingEntity create(
            OperationEntity operation,
            MeetingCategory meetingCategory,
            AttendeeScope attendeeScope,
            MemberEntity responsiblePerson,
            String location) {
        return new MeetingEntity(
                null,
                operation,
                meetingCategory,
                attendeeScope,
                MeetingStatus.SCHEDULED,
                responsiblePerson,
                location,
                null,
                null);
    }

    /*
     * 상태 전이(OPS-026). 전이표(TR-M1~TR-M4)에 있는 조합만 통과하고 나머지는 전부 막는다.
     * 미처리 안건 존재 여부는 다른 테이블(mtg_dtl)에 있어 엔티티가 스스로 셀 수 없으므로
     * 사실만 넘겨받는다 — 하위 업무 전이(SubWorkEntity)와 같은 경계다.
     */
    public void applyTransition(
            MeetingTransitionAction action, String reason, boolean hasUnresolvedAgenda) {
        switch (action) {
            case OPEN -> open();
            case WRITE_MINUTES -> writeMinutes();
            case CLOSE -> close(hasUnresolvedAgenda);
            case CANCEL -> cancel(reason);
        }
    }

    // TR-M1 개회
    private void open() {
        requireStatus(MeetingStatus.SCHEDULED);
        this.meetingStatus = MeetingStatus.IN_PROGRESS;
    }

    // TR-M2 회의록작성
    private void writeMinutes() {
        requireStatus(MeetingStatus.IN_PROGRESS);
        this.meetingStatus = MeetingStatus.MINUTES;
    }

    // TR-M3 종료. 미처리(PENDING) 안건이 남아 있으면 막는다 — 보류(HOLD)는 다음 회의로 이월하겠다는 의사 표시라 막지 않는다
    private void close(boolean hasUnresolvedAgenda) {
        requireStatus(MeetingStatus.MINUTES);
        if (hasUnresolvedAgenda) {
            throw new GeneralException(OperationErrorCode.AGENDA_UNRESOLVED);
        }
        this.meetingStatus = MeetingStatus.CLOSED;
    }

    // TR-M4 취소. 사유는 필수이며 공백만 있는 문자열도 거부한다 (VR-O06 준용)
    private void cancel(String reason) {
        requireStatus(MeetingStatus.SCHEDULED);
        if (reason == null || reason.isBlank()) {
            throw new GeneralException(OperationErrorCode.REASON_REQUIRED);
        }
        this.meetingStatus = MeetingStatus.CANCELED;
    }

    private void requireStatus(MeetingStatus required) {
        if (this.meetingStatus != required) {
            throw new GeneralException(OperationErrorCode.TRANSITION_NOT_ALLOWED);
        }
    }

    /*
     * 지금 안건을 상정·수정할 수 있는지 (OPS-027·OPS-028). 종료·취소된 회의는 막는다 — 끝난
     * 회의의 기록을 조용히 바꾸게 두면 회의록의 신뢰를 잃는다.
     */
    public void requireAgendaEditable() {
        if (this.meetingStatus == MeetingStatus.CLOSED
                || this.meetingStatus == MeetingStatus.CANCELED) {
            throw new GeneralException(OperationErrorCode.MEETING_CLOSED);
        }
    }

    /*
     * 지금 안건을 상정 철회(삭제)할 수 있는지 (OPS-029). 회의 시작 전(SCHEDULED)만 허용한다 —
     * 이미 진행된 회의에서 다뤄진 안건을 지우면 회의록에서 안건이 사라진다.
     */
    public void requireAgendaWithdrawable() {
        if (this.meetingStatus != MeetingStatus.SCHEDULED) {
            throw new GeneralException(OperationErrorCode.TRANSITION_NOT_ALLOWED);
        }
    }

    // 회의 책임자(의장) 본인인지. 개회·회의록작성·종료는 의장만 수행한다(TR-M1~M3) — 취소는 예외라 여기서 보지 않는다
    public boolean isChairedBy(MemberEntity member) {
        return this.responsiblePerson.getId().equals(member.getId());
    }
}
