package org.sscc.ssccopsserver.domain.operation.entity;

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

import org.hibernate.annotations.Check;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.operation.code.error.OperationErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * mtg_dtl(회의_상세) — 회의 안건.
 *
 * **안건은 운영 건을 가리키거나(연결 안건) 제목만 갖는다(드래프트 안건) — 둘 중 하나다**
 * (#625 · ADR-0059). 연결 안건의 제목은 그 운영 건의 oper_ttl이고 agnd_nm은 비어 있다. 드래프트
 * 안건은 operation이 NULL이고 agnd_nm이 제목이다. 드래프트는 promoteTo로 업무를 가리키는 연결
 * 안건이 되며, 되돌아가지 않는다.
 *
 * 이 모양은 한 번 걷혔다가 돌아왔다. ADR-0055(V24 · #593)는 독립 안건을 만들 길이 화면에 없어
 * 0건이라는 근거로 agnd_nm을 지웠고, 운영진이 «업무가 되기 전의 논의»를 안건으로 남길 길을
 * 요구하자 ADR-0059가 그것을 화면·승격과 함께 되살렸다(V30). 그래서 이번에는 상호 배타를
 * 서버 검증에만 맡기지 않고 DB CHECK(mtg_dtl_agnd_nm_oper_id_check)로도 건다 — 아래 @Check는
 * 같은 식을 H2(테스트의 ddl-auto: create)에도 걸기 위한 것이고, 배포 DB의 제약은 V30이 만든다.
 */
@Entity
@Table(name = "mtg_dtl", indexes = @Index(name = "idx_mtg_dtl_mtg_id", columnList = "mtg_id"))
@Check(
        name = "mtg_dtl_agnd_nm_oper_id_check",
        constraints = "(agnd_nm IS NULL) <> (oper_id IS NULL)")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class MeetingAgendaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "mtg_dtl_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mtg_id", nullable = false)
    private MeetingEntity meeting;

    // 드래프트 안건의 제목. 운영 건(operation)을 가리키는 안건은 NULL이다 (ADR-0059)
    @Column(name = "agnd_nm", length = 100)
    private String agendaName;

    @Enumerated(EnumType.STRING)
    @Column(name = "agnd_prcs_se_cd", length = 20)
    private AgendaProcessStatus processStatus;

    @Column(name = "agnd_seq")
    private Integer agendaOrder;

    // 안건이 다루는 운영 건. 연결 안건의 제목이 여기서 온다. 드래프트 안건은 NULL이다 (ADR-0059)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "oper_id")
    private OperationEntity operation;

    @Column(name = "agnd_cn", columnDefinition = "TEXT")
    private String content;

    @Column(name = "rslt_cn", columnDefinition = "TEXT")
    private String resultContent;

    // 안건 제출자. 등록자(등록 API를 부른 인증 주체)로 서버가 고정한다 — 클라이언트가 지정하지 않는다 (LY-05 준용)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "prsnr_id", nullable = false)
    private MemberEntity submitter;

    /*
     * 안건 상정(OPS-027)용 생성 팩토리. 처리 구분은 생략하면 PENDING(미처리)으로 서버가
     * 채운다 — 방금 올라온 안건이 이미 처리됐다고 볼 이유가 없다.
     *
     * operation과 agendaName은 정확히 하나만 준다. 요청 DTO(@AssertTrue)가 먼저 막지만 서비스를
     * 직접 부르는 경로에서도 성립해야 하는 규칙이라 여기서 한 번 더 본다 — 어긋남을 flush 시점의
     * CHECK 위반으로 미루면 원인이 500으로 흐려진다.
     */
    public static MeetingAgendaEntity create(
            MeetingEntity meeting,
            String agendaName,
            AgendaProcessStatus processStatus,
            int agendaOrder,
            OperationEntity operation,
            String content,
            MemberEntity submitter) {
        String name = normalizeName(agendaName);
        if ((operation == null) == (name == null)) {
            throw new GeneralException(OperationErrorCode.AGENDA_TARGET_INVALID);
        }
        return new MeetingAgendaEntity(
                null,
                meeting,
                name,
                processStatus == null ? AgendaProcessStatus.PENDING : processStatus,
                agendaOrder,
                operation,
                content,
                null,
                submitter);
    }

    // 드래프트 안건인지 — 아직 어느 운영 건도 가리키지 않고 제목만 가진 안건 (ADR-0059)
    public boolean isDraft() {
        return this.operation == null;
    }

    /*
     * 안건 수정(OPS-028). 정의서 비고 "논의 내용·처리 구분"대로 바꿀 수 있는 것은 이 셋이고,
     * 드래프트 안건이면 제목(agendaName)도 고칠 수 있다(ADR-0059 — 드래프트의 제목은 안건 자신이
     * 갖는 값이라서다). 어느 운영 건을 다루는지(operation)·제출자(submitter)는 다시 상정하는 것과
     * 다름없어 이 API의 범위 밖이다 — 드래프트를 운영 건에 잇는 길은 promoteTo 하나다.
     *
     * 등록(OPS-007) 계열의 선례처럼 **전체 교체**라 content·resultContent를 생략하면 지운 것으로
     * 본다 — processStatus는 요청 DTO에서 @NotNull로 막아 여기서는 널을 받지 않는다.
     * **agendaName만은 생략하면 그대로 둔다.** 드래프트의 제목을 비우면 CHECK가 깨지고, 이 필드가
     * 생기기 전부터 수정 요청을 보내던 화면은 그것을 싣지 않기 때문이다. 연결 안건에 제목을 주면
     * 400이다 — 그 안건의 제목은 운영 건의 제목이다.
     */
    public void update(
            String agendaName,
            String content,
            String resultContent,
            AgendaProcessStatus processStatus) {
        String name = normalizeName(agendaName);
        if (name != null) {
            if (!isDraft()) {
                throw new GeneralException(OperationErrorCode.AGENDA_TARGET_INVALID);
            }
            this.agendaName = name;
        }
        this.content = content;
        this.resultContent = resultContent;
        this.processStatus = processStatus;
    }

    /*
     * 드래프트 안건을 운영 건(승격으로 방금 만든 업무)에 잇는다 (#625 · ADR-0059). 제목은 이제 그
     * 운영 건에서 오므로 agendaName을 비운다 — 둘 다 채운 채 두면 CHECK가 깨지고 제목의 출처가
     * 둘이 된다. **되돌아가지 않는다** — 이미 연결된 안건은 409다.
     */
    public void promoteTo(OperationEntity target) {
        if (!isDraft()) {
            throw new GeneralException(OperationErrorCode.MEETING_AGENDA_ALREADY_LINKED);
        }
        this.operation = target;
        this.agendaName = null;
    }

    /*
     * 승격(#625)이 업무를 만들기 **전에** «이미 연결된 안건»을 끊는다. promoteTo도 같은 것을 보지만
     * 그때는 업무 INSERT가 이미 나간 뒤라, 롤백되더라도 쓸데없는 쓰기와 식별자 소모가 생긴다.
     */
    public void requireDraft() {
        if (!isDraft()) {
            throw new GeneralException(OperationErrorCode.MEETING_AGENDA_ALREADY_LINKED);
        }
    }

    // 공백뿐인 제목은 «제목 없음»과 같다 — 그 값으로 드래프트를 세우면 화면에 빈 안건이 생긴다
    private static String normalizeName(String agendaName) {
        return agendaName == null || agendaName.isBlank() ? null : agendaName;
    }
}
