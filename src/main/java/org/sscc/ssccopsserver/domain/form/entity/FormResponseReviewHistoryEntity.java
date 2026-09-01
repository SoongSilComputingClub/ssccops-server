package org.sscc.ssccopsserver.domain.form.entity;

import java.time.Instant;

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

import org.sscc.ssccopsserver.domain.form.code.ResponseReviewAction;
import org.sscc.ssccopsserver.domain.form.code.error.FormErrorCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * form_rspns_rvw_hstry(폼_응답_검토_이력) — 응답 한 건에 일어난 처리가 시간순으로 쌓인다 (#141).
 *
 * 그전까지 응답 심사는 rspns_stts_cd 한 컬럼을 덮어쓰는 일이라 mdfcn_dt 말고는 아무것도 남지
 * 않았다. 누가 승인했는지도, 왜 반려했는지도 어디에도 없었고 그래서 컨트롤러가 받은
 * @CurrentMember를 서비스로 넘기지도 않았다 — 넘기면 기록되는 것처럼 읽히기 때문이었다.
 * 이 테이블이 그 자리를 채운다.
 *
 * ── 사유를 응답 행의 컬럼으로 두지 않는 이유 ──────────────────
 * form_rspns_hstry에 rjct_rsn 하나를 더하는 방법이 더 짧지만, 재검토 때 직전 사유가 덮여
 * 사라진다. 화면이 보여주는 것은 마지막 사유가 아니라 처리 내역 **전부**이며(수정요청 →
 * 재제출 → 반려처럼 여러 번 오간 응답이 실제 대상이다), 덮어쓰는 컬럼으로는 그 화면을
 * 그릴 수 없다. sub_work_rjct(하위 업무 반려)가 같은 이유로 별도 테이블이다.
 *
 * ── 지우지 않는다 (POL-004) ────────────────────────────────
 * 모든 컬럼이 updatable = false다. 이력은 증거이므로 나중에 고칠 수 있으면 증거가 아니다 —
 * mbr_grd_hstry·mbr_stts_hstry(#78)와 같은 잠금이다. 삭제 메서드도 두지 않으며, 응답이
 * 지워질 일이 없으므로 cascade도 걸지 않는다.
 *
 * ── 처리자 이름을 복사하지 않는다 ──────────────────────────
 * prcs_mbr_id로 mbr을 가리키기만 하고 mbr_nm은 담지 않는다. 이름이 바뀌면 이력에 적힌 이름도
 * 함께 바뀌는 것이 맞다 — 개명한 운영자가 과거 이력에서만 옛 이름으로 남으면 그 행이 누구를
 * 가리키는지 화면에서 알 수 없다. 회원 정보를 응답 목록에 복사하지 않고 조인하는 것(#37)과
 * 같은 판단이며, N+1은 조회 쪽에서 @EntityGraph로 막는다
 * (FormResponseReviewHistoryRepository).
 *
 * ── SUBMIT도 여기 쌓인다 ───────────────────────────────────
 * 검토 이력인데 제출이 들어 있는 것은 타임라인이 "제출 → 수정요청 → 재제출 → 승인"으로 읽혀야
 * 하기 때문이다. 제출 행이 빠지면 회차가 언제 올라갔는지가 이력에서 사라져, 승인 의견이 어느
 * 제출본을 보고 쓴 것인지 알 수 없게 된다. 그 행의 처리자는 검토자가 아니라 응답자다.
 */
@Entity
@Table(
        name = "form_rspns_rvw_hstry",
        indexes =
                @Index(
                        name = "ix_form_rspns_rvw_hstry_response",
                        columnList = "form_rspns_id, prcs_dt"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class FormResponseReviewHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "form_rspns_rvw_hstry_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "form_rspns_id", nullable = false, updatable = false)
    private FormResponseHistoryEntity response;

    /*
     * 이 처리가 몇 번째 제출본에 대한 것이었는지 (form_rspns_hstry.sbmsn_seq의 복사본).
     *
     * 응답 행을 따라가 지금 회차를 읽으면 되는 것 아닌가 — 아니다. 그것은 **현재** 회차라,
     * 재제출이 한 번이라도 일어나면 과거 처리까지 새 회차로 보이게 된다. 이력 행이 굳는 시점의
     * 값을 그대로 박아 둬야 "1회차 제출 → 1회차 수정요청 → 2회차 제출 → 2회차 승인"으로 읽힌다.
     */
    @Column(name = "sbmsn_seq", nullable = false, updatable = false)
    private int submissionSequence;

    @Enumerated(EnumType.STRING)
    @Column(name = "rvw_prcs_se_cd", nullable = false, length = 20, updatable = false)
    private ResponseReviewAction action;

    /*
     * 처리자. 요청 본문이 아니라 @CurrentMember에서 온 회원이며(#78이 세운 규칙), 받아 주면
     * "누가 했는가"를 스스로 적어 넣을 수 있어 이력이 증거가 되지 못한다.
     *
     * NOT NULL이다 — 제출 행의 처리자는 응답자, 검토 행의 처리자는 검토자라 비는 경우가 없다.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "prcs_mbr_id", nullable = false, updatable = false)
    private MemberEntity processor;

    /*
     * 검토 의견. 승인은 비어 있을 수 있어 nullable이고, 수정요청·반려는 record()가 막는다.
     * 길이를 제한하지 않는 것은 무엇을 어떻게 고치라는 안내가 한두 줄로 끝나지 않기 때문이다
     * (sub_work_rjct.rjct_rsn과 같은 TEXT).
     */
    @Column(name = "rvw_opnn_cn", columnDefinition = "TEXT", updatable = false)
    private String opinion;

    @Column(name = "prcs_dt", nullable = false, updatable = false)
    private Instant processedAt;

    /*
     * 이력 한 줄을 만든다. 회차는 인자로 받지 않고 응답 행에서 읽는다 — 호출부가 넘기게 두면
     * 응답 행의 회차와 이력의 회차가 갈릴 수 있고, 그 어긋남은 타임라인이 굳은 뒤에야 드러난다.
     *
     * **검토 의견 필수 규칙을 강제하는 유일한 자리다.** 필요 여부의 판단은
     * ResponseReviewAction.requiresOpinion이 갖고 여기서는 그 답에 따라 끊기만 한다. 서비스가
     * 아니라 이력 엔티티에 두는 것은 검토를 기록하는 경로가 늘어도 규칙이 복제되지 않게
     * 하기 위해서다 (LY-02 · sub_work_rjct의 REASON_REQUIRED와 같은 자리).
     *
     * 공백만 있는 문자열은 없는 것으로 본다. DB의 NOT NULL이 잡지 못하는 자리이고, 통과시키면
     * 이력 행은 남지만 "왜"가 비어 있어 증거로 쓸 수 없다. 앞뒤 공백은 다듬어 저장한다.
     */
    public static FormResponseReviewHistoryEntity record(
            FormResponseHistoryEntity response,
            ResponseReviewAction action,
            MemberEntity processor,
            String opinion,
            Instant processedAt) {

        String trimmedOpinion = (opinion == null || opinion.isBlank()) ? null : opinion.trim();
        if (action.requiresOpinion() && trimmedOpinion == null) {
            throw new GeneralException(FormErrorCode.REVIEW_OPINION_REQUIRED);
        }

        return new FormResponseReviewHistoryEntity(
                null,
                response,
                response.getSubmissionSequence(),
                action,
                processor,
                trimmedOpinion,
                processedAt);
    }
}
