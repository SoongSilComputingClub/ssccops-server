package org.sscc.ssccopsserver.domain.member.repository;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/*
 * 회원 하드 삭제를 **막는** 참조의 표 (#361 · ADR-0021).
 *
 * mbr을 가리키는 FK 29개 중 본인 데이터 9개는 V9가 ON DELETE CASCADE로 바꿨고, 나머지 20개
 * — 이 회원이 남의 것에 한 일(작성자·검토자·변경자·담당자·발급자…) — 는 NO ACTION 그대로다.
 * 그 20개에 학술 활동의 원본 기획안(acdm_actv.form_rspns_id → form_rspns_hstry, 2차)을 더한
 * 21개가 여기 있다. 삭제가 FK 위반으로 실패하면 이 표가 제약 이름을 사람 표기로 번역하고,
 * 삭제 미리보기의 blockedBy는 같은 표의 matchSql로 무엇이 막을지를 미리 센다.
 *
 * ── 왜 표를 코드에 두는가 ─────────────────────────────────────
 * 판정 자체는 DB가 한다 — 이 표가 빠지거나 갈려도 삭제는 여전히 막힌다. 표가 주는 것은
 * **문구**뿐이다(«폼 작성자»가 없으면 운영진은 어느 화면에 가서 무엇을 정리해야 하는지 모른다).
 * 그래서 이 표와 V9가 어긋났을 때의 실패는 500이 아니라 "번역되지 않은 409"다.
 *
 * ── 이름 매칭이 대소문자를 가리지 않는 이유 ──────────────────────
 * PostgreSQL은 V1의 제약 이름을 소문자로(fknn5r…), H2(테스트 · ddl-auto: create)는 Hibernate가
 * 같은 해시를 대문자로(FKNN5R…) 만든다. V5가 손으로 지은 이름(fk_sub_work_chck_list_hstry_prfmr)은
 * H2에서 다른 해시가 되므로 이름으로 못 찾으면 **테이블·컬럼 이름**으로 한 번 더 찾는다 — H2의
 * 오류 문구가 `PUBLIC.SUB_WORK_CHCK_LIST_HSTRY FOREIGN KEY(PRFMR_ID)`를 싣기 때문이다.
 *
 * ── matchSql이 «남의 것»만 고르는 이유 ───────────────────────────
 * 변경 이력의 변경자, 응답 검토 이력의 처리자, 참가의 등록 처리자는 **본인일 수 있다**(본인
 * 수정 · 제출 행의 처리자는 응답자 · 자기 신청). 그 행은 mbr_id 쪽 cascade로 먼저 지워지므로
 * 삭제를 막지 않는다 — NO ACTION은 문장 끝에 검사한다. 미리보기가 그 행까지 세면 지워질 회원을
 * "막힌다"고 답하므로, 행이 살아남는 조건(`mbr_id <> :memberId`)을 함께 건다.
 */
public final class MemberReferenceConstraints {

    /**
     * 막는 참조 하나.
     *
     * @param constraintName V1·V2·V5의 제약 이름(PostgreSQL 기준)
     * @param table 참조하는 테이블
     * @param column 참조하는 컬럼
     * @param label 화면에 보여줄 표기
     * @param matchSql 이 회원을 가리키며 **삭제 뒤에도 남을** 행을 고르는 `FROM … WHERE …` 절 — `:memberId` 하나를 바인딩한다.
     *     앞에 SELECT를 붙이는 것은 질의 쪽(MemberDeletionQueryRepository)이다
     */
    public record Reference(
            String constraintName, String table, String column, String label, String matchSql) {

        boolean matchesName(String name) {
            return constraintName.equalsIgnoreCase(name);
        }

        /** H2 문구 대비 — `TABLE FOREIGN KEY(COLUMN)` 꼴이 메시지에 있으면 이 참조다 */
        boolean matchesMessage(String upperMessage) {
            return upperMessage.contains(
                    (table + " FOREIGN KEY(" + column + ")").toUpperCase(Locale.ROOT));
        }
    }

    private static final String P = ":memberId";

    /** 삭제를 막는 참조 21개. 순서는 미리보기 blockedBy의 순서다 — 운영진이 자주 마주칠 것을 앞에 */
    public static final List<Reference> BLOCKING =
            List.of(
                    new Reference(
                            "fknn5rhjtcayw32pk718rr8hugc",
                            "form",
                            "creatr_mbr_id",
                            "폼 작성자",
                            "FROM form WHERE creatr_mbr_id = " + P),
                    new Reference(
                            "fkq5cq3ckdjlss8d3t1scvdc0mq",
                            "event",
                            "creatr_mbr_id",
                            "행사 작성자",
                            "FROM event WHERE creatr_mbr_id = " + P),
                    new Reference(
                            "fkt4wy1omf82jvgb8uioe9t3br8",
                            "form_tmpl",
                            "creatr_mbr_id",
                            "폼 템플릿 작성자",
                            "FROM form_tmpl WHERE creatr_mbr_id = " + P),
                    new Reference(
                            "fke2id6suu5ht5m6h9hdmnlyitr",
                            "form_rspns_rvw_hstry",
                            "prcs_mbr_id",
                            "폼 응답 검토자",
                            "FROM form_rspns_rvw_hstry h JOIN form_rspns_hstry r"
                                    + " ON r.form_rspns_id = h.form_rspns_id"
                                    + " WHERE h.prcs_mbr_id = "
                                    + P
                                    + " AND r.mbr_id <> "
                                    + P),
                    new Reference(
                            "fkq7thgsl59m5n6q4jq5w0sy4eh",
                            "form_qitem_hstry",
                            "chnrg_mbr_id",
                            "폼 문항 변경자",
                            "FROM form_qitem_hstry WHERE chnrg_mbr_id = " + P),
                    new Reference(
                            "fkk6jvxf63jtaal1hlnabs4jwov",
                            "event_ptcp",
                            "rgtr_mbr_id",
                            "행사 참가자 등록 처리자",
                            "FROM event_ptcp WHERE rgtr_mbr_id = " + P + " AND mbr_id <> " + P),
                    new Reference(
                            "fkbomrgnpyh5747r94hsrahg6rb",
                            "oper",
                            "oper_rgtr_id",
                            "운영 업무 등록자",
                            "FROM oper WHERE oper_rgtr_id = " + P),
                    new Reference(
                            "fki8cidpgn4oxv9d58bjea0rd0h",
                            "oper",
                            "pic_id",
                            "운영 업무 담당자",
                            "FROM oper WHERE pic_id = " + P),
                    new Reference(
                            "fk2sofgwcglk06ivbgcg7ejpx9g",
                            "sub_work_stts_hstry",
                            "prfmr_id",
                            "하위 업무 상태 변경 수행자",
                            "FROM sub_work_stts_hstry WHERE prfmr_id = " + P),
                    new Reference(
                            "fk_sub_work_chck_list_hstry_prfmr",
                            "sub_work_chck_list_hstry",
                            "prfmr_id",
                            "하위 업무 점검 목록 변경자",
                            "FROM sub_work_chck_list_hstry WHERE prfmr_id = " + P),
                    new Reference(
                            "fker65s5itbngodwxdhxgwiowl0",
                            "mtg",
                            "mtg_rbprsn_id",
                            "회의 주재자",
                            "FROM mtg WHERE mtg_rbprsn_id = " + P),
                    new Reference(
                            "fkpetc32mu03nw1fv2ofbyeaogh",
                            "mtg_dtl",
                            "prsnr_id",
                            "회의 안건 발표자",
                            "FROM mtg_dtl WHERE prsnr_id = " + P),
                    new Reference(
                            "fk4qc86y2528bo70i44yye2ufld",
                            "acdm_actv",
                            "leadr_mbr_id",
                            "학술 활동 리더",
                            "FROM acdm_actv WHERE leadr_mbr_id = " + P),
                    new Reference(
                            "fke98fewlujgtdj4ae54s31hksj",
                            "acdm_actv",
                            "prpsr_mbr_id",
                            "학술 활동 제안자",
                            "FROM acdm_actv WHERE prpsr_mbr_id = " + P),
                    new Reference(
                            "fkhiuwj452c00txin1gj6d07es",
                            "acdm_actv",
                            "form_rspns_id",
                            "학술 활동의 원본 기획안",
                            "FROM acdm_actv a JOIN form_rspns_hstry r"
                                    + " ON r.form_rspns_id = a.form_rspns_id WHERE r.mbr_id = "
                                    + P),
                    new Reference(
                            "fk5ow6bmkqtrxb5ugl9ppkl96i8",
                            "acdm_actv_aprv",
                            "autzr_mbr_id",
                            "학술 활동 승인자",
                            "FROM acdm_actv_aprv WHERE autzr_mbr_id = " + P),
                    new Reference(
                            "fkh5voxfyy1gft8laf20domb0yr",
                            "sesn",
                            "rgtr_mbr_id",
                            "학술 회차 등록자",
                            "FROM sesn WHERE rgtr_mbr_id = " + P),
                    new Reference(
                            "fkb6qwbj5hq3najqatd14rb4kdd",
                            "mbr_grd_hstry",
                            "chnrg_mbr_id",
                            "회원 등급 변경자",
                            "FROM mbr_grd_hstry WHERE chnrg_mbr_id = " + P + " AND mbr_id <> " + P),
                    new Reference(
                            "fkp1ojiaq3u4nn8qihy3ho2abab",
                            "mbr_stts_hstry",
                            "chnrg_mbr_id",
                            "회원 상태 변경자",
                            "FROM mbr_stts_hstry WHERE chnrg_mbr_id = "
                                    + P
                                    + " AND mbr_id <> "
                                    + P),
                    new Reference(
                            "fkdq0mb3w1ylvpmkrl8noiid64f",
                            "mbr_chg_hstry",
                            "chnrg_mbr_id",
                            "회원 정보 변경자",
                            "FROM mbr_chg_hstry WHERE chnrg_mbr_id = " + P + " AND mbr_id <> " + P),
                    new Reference(
                            "fklls20mda396fxtj1ts4p7db5i",
                            "shr_lnk",
                            "iss_mbr_id",
                            "공유 링크 발급자",
                            "FROM shr_lnk WHERE iss_mbr_id = " + P));

    private MemberReferenceConstraints() {}

    /** 제약 이름으로 찾는다 — 미리보기 질의가 돌려준 키를 표기로 바꾸는 자리 */
    public static Optional<Reference> byConstraintName(String constraintName) {
        if (constraintName == null) {
            return Optional.empty();
        }
        return BLOCKING.stream().filter(r -> r.matchesName(constraintName)).findFirst();
    }

    /*
     * 예외에서 막은 참조를 찾는다. 원인 사슬의 메시지를 전부 이어 붙여 제약 이름 → 테이블·컬럼
     * 순으로 본다. Hibernate의 ConstraintViolationException.getConstraintName()에만 기대지 않는
     * 것은 H2 방언이 참조 무결성 위반(23503)에서 그 값을 문구 전체로 돌려주기 때문이다.
     */
    public static Optional<Reference> resolve(Throwable ex) {
        StringBuilder messages = new StringBuilder();
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t.getMessage() != null) {
                messages.append(t.getMessage()).append('\n');
            }
        }
        String upper = messages.toString().toUpperCase(Locale.ROOT);
        return BLOCKING.stream()
                .filter(r -> upper.contains(r.constraintName().toUpperCase(Locale.ROOT)))
                .findFirst()
                .or(() -> BLOCKING.stream().filter(r -> r.matchesMessage(upper)).findFirst());
    }
}
