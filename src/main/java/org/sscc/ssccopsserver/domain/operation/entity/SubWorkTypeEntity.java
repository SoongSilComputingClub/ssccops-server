package org.sscc.ssccopsserver.domain.operation.entity;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

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
 * 등록·수정 API(OPS-019)는 아직 없어 정적 팩토리를 열지 않았다. 행은 data.sql이 넣는다.
 * 승인 처리(OPS-014)가 붙기 전까지 등록이 실제로 읽는 값은
 * aprv_need_yn과 cmptn_chck_artcl_cn 둘뿐이고, 나머지는 매핑만 해 둔다.
 */
@Entity
@Table(name = "sub_work_type")
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
     * 승인 주체 역할 코드(PRESIDENT/VICE_PRESIDENT/TREASURER/DIRECTOR). 승인이 필요 없는
     * 유형(aprv_need_yn = FALSE)에서는 NULL이다 — 승인 주체만 있고 승인은 거치지 않는
     * 모순된 상태를 만들지 않기 위해서다. 실제 승인자 판정은 승인 처리(OPS-014)에서 붙는다.
     */
    @Column(name = "autzr_role_cd", length = 20)
    private String authorizerRoleCode;

    @Column(name = "min_need_agre_cnt_yn", nullable = false)
    private boolean minAgreeCountNeeded;

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
