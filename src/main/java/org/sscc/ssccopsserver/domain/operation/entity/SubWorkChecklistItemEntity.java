package org.sscc.ssccopsserver.domain.operation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * sub_work_chck_list(하위 업무 점검 목록) — 완료 전이의 판정 근거가 되는 체크리스트 항목.
 *
 * 등록 시 유형(sub_work_type)의 완료 점검 항목을 복사해 만든다. 유형을 참조만 하지 않고
 * 복사하는 것은, 등록 이후 유형 정책이 바뀌어도 이미 등록된 하위 업무의 완료 조건은
 * 그대로여야 하기 때문이다 (POL-005 — 정책 변경은 다음 등록부터 반영).
 *
 * 체크 상태 변경은 별도 API(OPS-012·013)의 몫이라 여기서는 변경 메서드를 열지 않는다.
 */
@Entity
@Table(name = "sub_work_chck_list")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SubWorkChecklistItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sub_work_chck_list_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sub_work_id", nullable = false)
    private SubWorkEntity subWork;

    @Column(name = "chck_artcl_cn", nullable = false, columnDefinition = "TEXT")
    private String article;

    @Column(name = "cmptn_yn", nullable = false)
    private boolean completed;

    // 화면 표시 순서. 유형에 적힌 항목 순서를 1부터 매긴다
    @Column(name = "sort_seq", nullable = false)
    private Integer sortOrder;

    public static SubWorkChecklistItemEntity create(
            SubWorkEntity subWork, String article, int sortOrder) {
        return new SubWorkChecklistItemEntity(null, subWork, article, false, sortOrder);
    }
}
