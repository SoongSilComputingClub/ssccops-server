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
 * mtg_dtl(회의_상세) — 회의 안건. 컬럼정의서의 Seq 3이 결번인데 agnd_nm 설명이 "운영건ID가
 * NULL일 때"를 전제하고 있어, 프론트(entities/meeting/model/types.ts의 @db-pending)와
 * 같은 결론으로 누락된 oper_id FK 컬럼을 여기 매핑한다 — 데이터사전 원본(xlsx)에는 아직
 * 반영되지 않았으니 사전 갱신 시 이 Seq 자리에 넣을 것 (ssccops-web#56 · ssccops#83).
 *
 * agndNm과 operation은 상호 배타적이다: 운영 건에 연결된 안건은 그 oper_ttl을 제목으로
 * 쓰므로 agnd_nm이 NULL이고, 독립 안건은 반대로 operation이 NULL이고 agnd_nm이 채워진다
 * (OPS-027 "둘 중 하나 필수").
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
    @Column(name = "agnd_nm", length = 100)
    private String agendaName;

    @Enumerated(EnumType.STRING)
    @Column(name = "prcs_se_cd", length = 20)
    private AgendaProcessStatus processStatus;

    @Column(name = "agnd_seq")
    private Integer agendaOrder;

    // 안건이 다루는 운영 건. 독립 안건(agendaName만 있는 경우)은 NULL이다
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
            String agendaName,
            AgendaProcessStatus processStatus,
            int agendaOrder,
            OperationEntity operation,
            String content,
            MemberEntity submitter) {
        return new MeetingAgendaEntity(
                null,
                meeting,
                agendaName,
                processStatus == null ? AgendaProcessStatus.PENDING : processStatus,
                agendaOrder,
                operation,
                content,
                null,
                submitter);
    }

    /*
     * 안건 수정(OPS-028). 정의서 비고 "논의 내용·처리 구분"대로 바꿀 수 있는 것은 이 셋뿐이다
     * — 어느 운영 건을 다루는지(operation)·제목(agendaName)·제출자(submitter)는 다시 상정하는
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
