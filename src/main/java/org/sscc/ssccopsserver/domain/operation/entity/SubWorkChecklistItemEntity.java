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
 * 체크 상태만 바뀐다. 항목 내용(chck_artcl_cn)과 순서(sort_seq)는 유형에서 복사된 값이라
 * 변경 메서드를 열지 않는다 — 완료 조건을 사후에 낮추는 경로를 만들지 않기 위해서다.
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

    /*
     * 체크·해제 (OPS-013 · REQ-021). 완료 조건을 되돌릴 수 있어야 하므로 체크 전용 메서드가
     * 아니라 값을 받는다 — 화면의 체크박스가 같은 자리에서 켜고 끄기 때문이다.
     *
     * 언제 바뀔 수 있는지는 항목이 아니라 하위 업무의 상태가 정하므로(완료된 건은 못 바꾼다)
     * 여기서 판단하지 않고 SubWorkEntity.requireChecklistEditable()이 먼저 막는다.
     * 같은 값을 다시 넣어도 결과가 같다 — 체크는 그 자체로 멱등이다.
     */
    public void updateCompletion(boolean completed) {
        this.completed = completed;
    }
}
