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

import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * mtg_dtl(회의_상세) — 회의 안건.
 *
 * **안건은 언제나 운영 건을 가리킨다**(#593 · ADR-0055). 제목은 그 운영 건의 oper_ttl이고 안건이
 * 따로 제목을 갖지 않는다.
 *
 * 그전에는 agnd_nm(안건_명)과 oper_id가 상호 배타였고 독립 안건(agnd_nm만 있는 안건)이 설계에
 * 있었다(OPS-027 "둘 중 하나 필수"). **그런데 어드민 화면에 그것을 만들 길이 없었다** — 안건
 * 상정이 언제나 agendaName: null을 보낸다. 실제 데이터도 전부 연결형이었고, MCP가 설계대로 그
 * 길을 열면서 어긋남이 드러났다(#591). 쓰는 모양 하나만 남긴 것이 이 결정이다.
 *
 * 그래서 operation은 nullable = false이고 V24가 agnd_nm을 지우며 oper_id에 NOT NULL을 걸었다 —
 * 운영 건이 없던 기존 안건은 그 마이그레이션이 지웠다(되돌릴 수 없는 부분은 스키마가 아니라
 * 그 데이터다 · ADR-0055 "뒤집는다면").
 */
@Entity
@Table(name = "mtg_dtl", indexes = @Index(name = "idx_mtg_dtl_mtg_id", columnList = "mtg_id"))
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

    // 운영건ID(operation)가 NULL일 때만 쓰는 독립 안건 제목
    @Enumerated(EnumType.STRING)
    @Column(name = "agnd_prcs_se_cd", length = 20)
    private AgendaProcessStatus processStatus;

    @Column(name = "agnd_seq")
    private Integer agendaOrder;

    // 안건이 다루는 운영 건. 안건의 제목이 여기서 온다 (ADR-0055)
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
     */
    public static MeetingAgendaEntity create(
            MeetingEntity meeting,
            AgendaProcessStatus processStatus,
            int agendaOrder,
            OperationEntity operation,
            String content,
            MemberEntity submitter) {
        return new MeetingAgendaEntity(
                null,
                meeting,
                processStatus == null ? AgendaProcessStatus.PENDING : processStatus,
                agendaOrder,
                operation,
                content,
                null,
                submitter);
    }

    /*
     * 안건 수정(OPS-028). 정의서 비고 "논의 내용·처리 구분"대로 바꿀 수 있는 것은 이 셋뿐이다
     * — 어느 운영 건을 다루는지(operation · 그것이 곧 제목이다)·제출자(submitter)는 다시 상정하는
     * 것과 다름없어 이 API의 범위 밖이다. 등록(OPS-007) 계열의 선례처럼 **전체 교체**라
     * content·resultContent를 생략하면 지운 것으로 본다 — processStatus는 요청 DTO에서
     * @NotNull로 막아 여기서는 널을 받지 않는다.
     */
    public void update(String content, String resultContent, AgendaProcessStatus processStatus) {
        this.content = content;
        this.resultContent = resultContent;
        this.processStatus = processStatus;
    }
}
