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
 * **항목 내용(chck_artcl_cn)은 이제 바뀔 수 있다** (#307). 그전까지는 변경 메서드가 없었고
 * 근거는 "완료 조건을 사후에 낮추는 경로를 만들지 않기 위해서"였다. 그 근거는 사라진 것이
 * 아니라 **두 잠금과 이력으로 옮겨 갔다**:
 *   - 항목 편집은 기획·진행까지만 가능하다 (SubWorkEntity.requireChecklistItemEditable).
 *     체크·해제를 막는 requireChecklistEditable보다 한 단계 세다 — 체크는 진척 기록이지만
 *     항목 편집은 판정 근거 자체를 바꾸는 일이라 성격이 다르다.
 *   - 삭제는 체크되지 않은 항목만 가능하다 (SubWorkServiceImpl.deleteChecklistItem).
 *   - 더하고 고치고 지운 것이 sub_work_chck_list_hstry에 전부 남는다.
 *
 * 순서(sort_seq)는 여전히 바뀌지 않는다. 추가는 끝에 붙고, 삭제는 남은 항목의 번호를 다시
 * 매기지 않는다 — 정렬 결과가 같아 구멍이 나도 화면이 달라지지 않는다. 순서 변경 API를 두지
 * 않은 이유는 SubWorkController의 체크리스트 절 주석에 있다.
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
     * 항목 문구 수정 (#307). 체크 상태는 건드리지 않는다 — 문구를 다듬는 것과 그 항목을
     * 해낸 것은 다른 사실이고, 문구를 고쳤다고 이미 한 일이 취소되지는 않는다.
     *
     * 언제 바꿀 수 있는지는 여기서 판단하지 않는다. updateCompletion과 같은 이유로 그것은
     * 항목이 아니라 하위 업무의 상태가 정하며, SubWorkEntity.requireChecklistItemEditable()이
     * 먼저 막는다. 이전 문구는 이력이 필요로 하므로 호출부가 바꾸기 전에 읽어 둔다.
     */
    public void changeArticle(String article) {
        this.article = article;
    }

    /*
     * 지금 이 항목을 지울 수 있는지 (#307). **두 잠금을 합친 답이다** — 하위 업무가
     * 기획·진행이어야 하고(checklistItemEditable) 그 항목이 체크되지 않았어야 한다.
     *
     * 둘을 한 값으로 묶어 내려 보내는 것은 화면이 둘을 AND로 엮는 규칙을 자기가 가지지 않게
     * 하기 위해서다 — 규칙이 두 곳에 적히면 갈리고, 그 어긋남은 버튼은 보이는데 누르면 409가
     * 나는 자리로만 드러난다 (#121·#194).
     *
     * 상태는 항목이 알 수 없는 사실이라 인자로 받는다 — 연관을 타고 들어가면 목록을 그릴 때마다
     * 또 묻게 된다. SubWorkEntity.isReadyForReview·progressRate가 개수를 넘겨받는 것과 같다.
     */
    public boolean isDeletable(boolean checklistItemEditable) {
        return checklistItemEditable && !this.completed;
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
