package org.sscc.ssccopsserver.domain.academicprogram.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.sscc.ssccopsserver.domain.event.entity.EventParticipantEntity;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * attendance(회차별 출석) — 회차 하나에 대한 참가자별 참석 여부 (#135, 학술관리_데이터모델.md
 * §2). 회차 기록 화면이 진행 내용과 출석 체크를 한 화면·단일 제출 버튼으로 받으므로, 이 행들은
 * session과 같은 트랜잭션에서 함께 만들어진다.
 *
 * 출석 대상은 신청자(폼 응답)가 아니라 event_ptcp(확정 팀원)다 — 대기자·취소자는 출석부에
 * 존재할 수 없다(설계 결정 #3). 회원(mbr)이 아니라 참가자 행을 가리키는 것은 "이 활동의
 * 팀원으로서" 출석했다는 뜻이어야 하기 때문이다.
 *
 * (session_id, event_ptcp_id) UNIQUE는 ERD에 없지만 여기서 건다 — 한 회차에 같은 참가자가 두
 * 줄이면 presentCount/totalCount가 조용히 부풀고, 어느 줄이 맞는지 답할 수 있는 규칙이 없다.
 * 그래서 재제출의 출석 교체는 통째로 지웠다 넣지 않고 차집합만 움직인다
 * (FormLabelServiceImpl.replaceFormLabels와 같은 이유 — 같은 쌍을 한 트랜잭션에서 지웠다 넣으면
 * Hibernate가 INSERT를 DELETE보다 먼저 흘려보내 UNIQUE 제약에 걸린다).
 *
 * 감사 컬럼을 두지 않는 것은 session과 같은 이유다.
 */
@Entity
@Table(
        name = "attendance",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_attendance_session_participant",
                        columnNames = {"session_id", "event_ptcp_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AttendanceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "attendance_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false, updatable = false)
    private SessionEntity session;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_ptcp_id", nullable = false, updatable = false)
    private EventParticipantEntity participant;

    @Column(name = "present_yn", nullable = false)
    private boolean present;

    public static AttendanceEntity of(
            SessionEntity session, EventParticipantEntity participant, boolean present) {
        return new AttendanceEntity(null, session, participant, present);
    }

    /** 재제출·출석 정정의 유일한 변경 지점. 대상(참가자)은 바뀌지 않고 체크 값만 바뀐다 */
    public void changePresent(boolean present) {
        this.present = present;
    }
}
