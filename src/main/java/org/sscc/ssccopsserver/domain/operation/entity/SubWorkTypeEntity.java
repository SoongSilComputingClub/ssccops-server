package org.sscc.ssccopsserver.domain.operation.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.sscc.ssccopsserver.domain.operation.code.error.OperationErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * sub_work_type(하위 업무 유형) — 승인 정책과 완료 조건을 담는 기준 데이터.
 *
 * 유형이 enum이 아니라 테이블인 것은 의도된 것이다. 승인 주체·정족수·기준 금액을 코드가
 * 아닌 데이터로 두어 재배포 없이 바꾸는 것이 REQ-010이자 API 정의서 POL-005다.
 * 그래서 등록 요청도 유형 이름이 아니라 subWorkTypeId를 받는다.
 *
 * 등록·수정은 관리 화면(OPS-019 · #43)이 하고, 시드 4종은 data.sql이 넣는다.
 * 승인 처리(OPS-014)가 붙기 전까지 등록이 실제로 읽는 값은
 * aprv_need_yn과 cmptn_chck_artcl_cn 둘뿐이고, 나머지는 매핑만 해 둔다.
 *
 * use_yn과 감사 컬럼은 관리 화면(#43)이 요구해 먼저 붙인 자리다. 유형은 하위 업무가 FK로
 * 참조하므로 지우지 못하고 use_yn을 내리며, 승인 정책을 화면에서 고칠 수 있게 되는 이상
 * "언제 바뀌었는가"가 남아야 한다. 둘 다 form_lbl의 대응 컬럼과 같은 규칙이다.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "sub_work_type",
        uniqueConstraints =
                @UniqueConstraint(name = "uk_sub_work_type_name", columnNames = "type_nm"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubWorkTypeEntity {

    // 완료 점검 항목은 한 줄에 하나씩 적는다. 개행 표기(\n·\r\n)를 가리지 않도록 \R로 자른다
    private static final Pattern CHECK_ARTICLE_DELIMITER = Pattern.compile("\\R");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sub_work_type_id")
    private Long id;

    @Column(name = "type_nm", nullable = false, length = 100)
    private String typeName;

    // 이 유형의 하위 업무가 승인을 거쳐야 하는지 (REQ-016 저위험 업무 승인 면제)
    @Column(name = "aprv_need_yn", nullable = false)
    private boolean approvalNeeded;

    /*
     * 승인 주체 역할 코드(PRESIDENT/VICE_PRESIDENT/TREASURER/DIRECTOR). 이 역할을 가진
     * 사람만 이 유형의 하위 업무를 최종 승인(TR-03 승인·완료)할 수 있다.
     *
     * 승인이 필요 없는 유형(aprv_need_yn = FALSE)에서는 NULL이다 — 승인 주체만 있고 승인은
     * 거치지 않는 모순된 상태를 만들지 않기 위해서다. 실제 승인자 판정은 승인 처리(OPS-014)에서
     * 붙는다. 아래 approveAndComplete 주석대로 지금은 아무도 이 값을 강제하지 않는다.
     */
    @Column(name = "autzr_role_cd", length = 20)
    private String authorizerRoleCode;

    /*
     * 의사결정 방식. FALSE면 단독 — 승인자가 승인 한 번으로 최종 승인을 끝낸다.
     * TRUE면 정족수 — 찬성 투표가 min_need_agre_cnt만큼 모여야 비로소 승인자가 최종 승인할
     * 수 있다 (POL-007, O-03 확정).
     *
     * 정족수는 승인자를 대체하는 경로가 아니다. 찬성이 아무리 모여도 승인자가 누르지 않으면
     * 완료되지 않고, 반대로 승인자라도 정족수 전에는 누를 수 없다. 그래서 정족수는
     * 승인이 필요한 유형에서만 의미가 있다.
     */
    @Column(name = "min_need_agre_cnt_yn", nullable = false)
    private boolean minAgreeCountNeeded;

    /*
     * 최소 필요 동의 수. 정족수 유형에서만 값이 있고 1 이상이다.
     * 1도 단독과 다르다 — 단독은 투표 없이 승인자가 바로 누르지만, 정족수 1은 다른 한 명의
     * 찬성이 먼저 있어야 한다.
     */
    @Column(name = "min_need_agre_cnt")
    private Integer minAgreeCount;

    // 위험도 판정 기준 금액. 지출 유형에서만 의미가 있다.
    // 표준도메인 금액N15는 소수 자리가 없는 NUMERIC(15)라 scale을 두지 않는다
    @Column(name = "crtr_amt", precision = 15)
    private BigDecimal criterionAmount;

    @Column(name = "expnd_yn", nullable = false)
    private boolean expenditure;

    @Column(name = "cmptn_chck_artcl_cn", columnDefinition = "TEXT")
    private String completionCheckArticle;

    /*
     * 사용 여부. 비활성 유형은 하위 업무를 새로 등록할 때 고를 수 없을 뿐, 이미 그 유형으로
     * 등록된 하위 업무는 그대로 남는다 (form_lbl.use_yn과 같은 축). 이름이 usable이 아니라
     * active인 것은 화면이 "사용/미사용"으로 보여주기 때문이며, 컬럼명은 데이터사전을 따른다.
     */
    @Column(name = "use_yn", nullable = false)
    private boolean active;

    /*
     * 감사 컬럼에는 DB 기본값을 두지 않는다 — 값은 JPA Auditing이 채운다. 대신 JPA를 거치지
     * 않는 data.sql 시드는 두 컬럼을 직접 넣어야 한다. 시드 행을 추가할 때 빠뜨리면 기동이 깨진다.
     */
    @CreatedDate
    @Column(name = "crt_dt", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "mdfcn_dt", nullable = false)
    private Instant updatedAt;

    /*
     * 유형 등록 (OPS-019). 새 유형은 항상 활성이다 — 만들자마자 못 쓰게 할 이유가 없다.
     *
     * 기준 금액·지출 여부는 받지 않는다. 관리 화면에서 입력란이 빠졌고(#43), expnd_yn이
     * NOT NULL이라 값은 있어야 하므로 FALSE로 둔다. 위험도 판정(REQ-016)이 붙을 때 열린다.
     */
    public static SubWorkTypeEntity create(
            String typeName,
            boolean approvalNeeded,
            String authorizerRoleCode,
            boolean minAgreeCountNeeded,
            Integer minAgreeCount,
            List<String> completionCheckArticles) {
        SubWorkTypeEntity type = new SubWorkTypeEntity();
        type.typeName = typeName;
        type.expenditure = false;
        type.active = true;
        type.apply(
                approvalNeeded,
                authorizerRoleCode,
                minAgreeCountNeeded,
                minAgreeCount,
                completionCheckArticles);
        return type;
    }

    /*
     * 유형 수정 (OPS-019). 폼 전체 저장이라 넘어온 값으로 통째로 덮는다.
     *
     * crtr_amt·expnd_yn·use_yn은 건드리지 않는다. 앞의 둘은 이 API의 범위 밖이라 덮으면
     * 시드된 예산지출이 저장 한 번에 지출 유형이 아니게 되고, use_yn은 목록의 토글이
     * 따로 바꾸는 값이라 폼 저장이 되돌려서는 안 된다.
     *
     * 바뀐 승인 규칙은 이미 등록된 하위 업무에 소급되지 않는다 — 하위 업무가 등록 시점에
     * 값을 복사해 가기 때문이며, 화면 하단 안내 문구와 같은 규칙이다.
     */
    public void update(
            String typeName,
            boolean approvalNeeded,
            String authorizerRoleCode,
            boolean minAgreeCountNeeded,
            Integer minAgreeCount,
            List<String> completionCheckArticles) {
        this.typeName = typeName;
        apply(
                approvalNeeded,
                authorizerRoleCode,
                minAgreeCountNeeded,
                minAgreeCount,
                completionCheckArticles);
    }

    /*
     * 사용 여부 전환 (OPS-019 /activation). 하위 업무가 FK로 참조하므로 유형은 지우지 못한다.
     * 비활성 유형은 새 하위 업무가 고를 수 없을 뿐, 이미 그 유형으로 등록된 건은 그대로다.
     */
    public void changeActivation(boolean active) {
        this.active = active;
    }

    /*
     * 승인 정책을 한 곳에서 맞춘다. 서비스가 아니라 여기 두는 것은 등록·수정 두 경로가
     * 같은 불변식을 지켜야 하고, 앞으로 경로가 늘어도 모순된 행이 생기면 안 되기 때문이다.
     *
     * 승인이 필요 없으면 승인자·정족수를 거절하지 않고 **지운다**. 화면에서 '승인 여부'를
     * 불필요로 바꿔도 승인자·의사결정 칩은 그대로 남아 있어 그 값이 함께 실려 오는데,
     * 이를 400으로 막으면 화면에서 유형을 저장할 방법이 없어진다. 유형과 무관한 잔여 속성은
     * 거절이 아니라 정리한다(폼 도메인 QuestionCompositionValidator와 같은 방침).
     *
     * 반대로 승인이 필요한데 승인자가 없거나 정족수 인원이 없는 것은 정리할 수 없다 —
     * 무엇으로 채울지 서버가 정할 수 없으므로 거절한다.
     */
    private void apply(
            boolean approvalNeeded,
            String authorizerRoleCode,
            boolean minAgreeCountNeeded,
            Integer minAgreeCount,
            List<String> completionCheckArticles) {
        this.completionCheckArticle = joinCheckArticles(completionCheckArticles);
        this.approvalNeeded = approvalNeeded;

        if (!approvalNeeded) {
            this.authorizerRoleCode = null;
            this.minAgreeCountNeeded = false;
            this.minAgreeCount = null;
            return;
        }
        if (authorizerRoleCode == null || authorizerRoleCode.isBlank()) {
            throw new GeneralException(OperationErrorCode.INVALID_APPROVAL_POLICY);
        }
        this.authorizerRoleCode = authorizerRoleCode;
        this.minAgreeCountNeeded = minAgreeCountNeeded;

        if (!minAgreeCountNeeded) {
            this.minAgreeCount = null;
            return;
        }
        // 1도 단독과 다르다 — 단독은 투표 없이 승인자가 바로 누르고, 정족수 1은 다른 한 명의
        // 찬성이 먼저 있어야 한다 (POL-007 O-03 확정)
        if (minAgreeCount == null || minAgreeCount < 1) {
            throw new GeneralException(OperationErrorCode.INVALID_APPROVAL_POLICY);
        }
        this.minAgreeCount = minAgreeCount;
    }

    /*
     * 이 유형의 하위 업무가 최종 승인(TR-03) 전에 찬성 투표를 모아야 하는지 (#47).
     *
     * 세 값을 따로 읽지 않고 여기서 한 번에 판정한다 — 승인을 거치지 않는 유형의 정족수,
     * 인원이 비어 있는 정족수는 성립하지 않는다. apply(...)가 그런 조합을 저장하지 못하게
     * 막고 있으나, 판정을 호출부에 흩어 두면 규칙이 두 벌이 된다.
     */
    public boolean requiresQuorum() {
        return approvalNeeded && minAgreeCountNeeded && minAgreeCount != null;
    }

    /*
     * 완료 점검 항목을 저장 형태(개행 구분 TEXT)로 되돌린다. 아래 completionCheckArticles()의
     * 역방향이며, 빈 항목은 버린다 — 남겨 두면 체크할 수 없는 빈 체크리스트 행이 생긴다.
     * 남는 항목이 없으면 NULL이다(체크리스트 없이 등록되는 유형).
     */
    private static String joinCheckArticles(List<String> articles) {
        if (articles == null) {
            return null;
        }
        String joined =
                articles.stream()
                        .filter(Objects::nonNull)
                        .map(String::strip)
                        .filter(article -> !article.isEmpty())
                        .collect(Collectors.joining("\n"));
        return joined.isEmpty() ? null : joined;
    }

    /*
     * 유형별 완료 점검 항목을 줄 단위로 끊어 준다. 하위 업무 등록 시 이 목록을 그대로
     * 체크리스트로 복사한다 (REQ-021 — 완료 체크리스트는 유형별로 정의한다).
     * 유형에 항목이 없으면 체크리스트 없이 등록된다.
     */
    public List<String> completionCheckArticles() {
        if (completionCheckArticle == null || completionCheckArticle.isBlank()) {
            return List.of();
        }
        return CHECK_ARTICLE_DELIMITER
                .splitAsStream(completionCheckArticle)
                .map(String::strip)
                .filter(article -> !article.isEmpty())
                .toList();
    }
}
