package org.sscc.ssccopsserver.domain.form.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * form_tmpl(폼_템플릿) — 자주 쓰는 문항 구성을 이름 붙여 보관해 두는 시작점 (#142).
 *
 * **폼에 플래그를 두지 않고 별도 테이블로 나눈 것이 이 이슈의 유일한 설계 결정이다.**
 * form에 tmpl_yn 같은 컬럼을 하나 더 두는 쪽이 테이블이 늘지 않아 짧아 보이지만, 템플릿에는
 * 폼의 대부분이 무의미하다 — 접수 기간도, 접수 상태도, 응답도 없다. 플래그로 두면
 * "OPEN이 될 수 없는 폼"·"응답이 있을 수 없는 폼"이라는 예외가 폼 로직 전역에 흩어지고
 * (FormReceiptPolicy·FormStatusAction·응답 집계가 전부 그 분기를 하나씩 갖게 된다),
 * 무엇보다 폼을 조회하는 모든 자리에 `AND tmpl_yn = false` 필터가 붙어야 한다. 그 필터를
 * 한 군데라도 빠뜨리면 템플릿이 접수 목록에 뜨고, 공개 링크가 열리고, 응답 집계에 섞인다.
 * 테이블을 나누면 그 필터가 애초에 존재하지 않는다.
 *
 * 대신 폼과 공유해야 하는 것이 하나 있다 — 문항 구성이다. qitem_cpst_cn은 폼과 **같은
 * QuestionCompositionContent 타입**을 쓰고, 검증도 같은 QuestionCompositionValidator를 쓴다.
 * 여기서 타입이나 규칙이 갈리면 템플릿으로 만든 폼이 저장에서 거절되는 상태가 생긴다.
 *
 * **템플릿은 공용이다.** creatr_mbr_id는 "누가 만들었나"를 남기는 감사용이지 접근 제어에
 * 쓰지 않는다 — 폼 자체에 소유자 개념이 약한데(누구나 남의 폼을 고치고 복제한다) 템플릿에만
 * 개인 소유를 도입하면 어휘가 두 벌이 된다.
 *
 * 지우는 대신 use_yn을 내린다. form_lbl·sub_work_type과 같은 축이며, 이유도 같다 —
 * 비활성 템플릿은 "새로 고를 수 없을" 뿐이고 되돌릴 수 있어야 한다. 그래서 DELETE가 없다.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "form_tmpl")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class FormTemplateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "form_tmpl_id")
    private Long id;

    /*
     * 생성자(mbr.mbr_id). 폼과 같이 인증 주체를 서버가 기록하며 클라이언트가 지정할 수 없다.
     * updatable = false인 것도 같은 이유다 — 템플릿을 넘겨받는 개념이 없고, 공용이라
     * 넘겨받을 이유도 없다. 접근 제어에 쓰이지 않으므로 이 값이 바뀌어도 아무 판정도
     * 달라지지 않지만, 그렇기 때문에 더더욱 기록으로서만 남아야 한다.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creatr_mbr_id", nullable = false, updatable = false)
    private MemberEntity creator;

    /*
     * 템플릿명. 폼 제목(form_ttl_nm)과 같은 V200이다 — 템플릿에서 만든 폼의 기본 제목이
     * 이 값이라, 더 짧게 잡으면 폼으로 옮기는 순간 잘릴 수 있는 길이가 생긴다.
     *
     * 라벨(lbl_nm)과 달리 UNIQUE를 걸지 않는다. 라벨은 화면에서 '고르는' 값이라 같은 이름이
     * 둘이면 어느 쪽을 골랐는지 알 수 없지만, 템플릿은 이름과 함께 설명·문항 수·수정 일시를
     * 보여주는 목록에서 고르므로 같은 이름이 둘이어도 구분된다. 오히려 "2026 신규모집"을
     * 해마다 다시 만드는 것이 정상 사용이라 UNIQUE는 그 길을 막는다.
     */
    @Column(name = "tmpl_nm", nullable = false, length = 200)
    private String name;

    /*
     * 설명. nullable이며 "언제 쓰는 템플릿인가"를 적는 자리다. 이름만으로는 문항 구성을
     * 짐작할 수 없어 목록에서 고르려면 한 줄이 더 필요하다.
     */
    @Column(name = "tmpl_expln", length = 500)
    private String description;

    /*
     * 문항 구성(JSONB). 매핑 방식은 FormEntity.questionComposition과 글자 그대로 같다 —
     * @JdbcTypeCode(SqlTypes.JSON)만 두고 columnDefinition에 'jsonb'를 박지 않는다
     * (테스트가 도는 H2에서 DDL이 깨진다). 역직렬화 실패를 도메인 오류로 옮기는 변환도
     * 같은 JsonFormatMapperConfig가 그대로 맡는다.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "qitem_cpst_cn", nullable = false)
    private QuestionCompositionContent questionComposition;

    /*
     * 사용 여부. 이름이 usable이 아니라 active인 것은 FormLabelEntity와 같은 이유다 —
     * 화면이 이 값을 "활성/비활성"으로 보여준다. 컬럼명(use_yn)은 데이터사전 표기를 따른다.
     */
    @Column(name = "use_yn", nullable = false)
    private boolean active;

    @CreatedDate
    @Column(name = "crt_dt", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "mdfcn_dt", nullable = false)
    private Instant updatedAt;

    /*
     * 템플릿 생성. 새로 만든 템플릿은 항상 활성이다 — 만들자마자 비활성인 템플릿은
     * 쓸모가 없다 (FormLabelEntity.create와 같은 판단).
     *
     * 상태(FormStatus)에 해당하는 값이 없는 것이 폼과 나뉜 이유 그 자체다. 템플릿은
     * 접수하지 않으므로 열 것도 닫을 것도 없다.
     */
    public static FormTemplateEntity create(
            MemberEntity creator,
            String name,
            String description,
            QuestionCompositionContent questionComposition) {
        return new FormTemplateEntity(
                null, creator, name, description, questionComposition, true, null, null);
    }

    /*
     * 템플릿 수정 (PUT). 문항 구성은 부분 갱신이 아니라 통째로 교체한다 — 근거는 폼과 같다
     * (QuestionCompositionContent가 모르는 필드를 보존하지 못하므로 병합하면 클라이언트가
     * 보내지 않은 항목이 조용히 사라진 채 반쯤 남는다).
     *
     * 사용 여부(use_yn)는 받지 않는다. 폼 수정이 상태를 받지 않는 것과 같은 갈래이며,
     * 이유도 같다 — 문항을 고치는 것과 템플릿을 내리는 것은 다른 행위다. 전환은
     * PATCH .../use 하나로 좁힌다.
     *
     * 생성자(creator)는 바꾸지 않는다. 컬럼 자체가 updatable = false다.
     */
    public void update(
            String name, String description, QuestionCompositionContent questionComposition) {
        this.name = name;
        this.description = description;
        this.questionComposition = questionComposition;
    }

    /*
     * 활성/비활성 전환. 켜기·끄기 전용 메서드가 아니라 값을 받는 것은 FormLabelEntity와
     * 같은 이유다 — 화면의 토글이 같은 자리에서 양쪽으로 움직인다. 같은 값을 다시 넣어도
     * 결과가 같다(멱등).
     */
    public void changeActive(boolean active) {
        this.active = active;
    }

    /*
     * 이 템플릿으로 새 폼을 시작할 수 있는 문항 구성. 깊은 복사인 것이 핵심이다 —
     * record 자체는 불변이지만 안에 든 List·Map은 Jackson이 만든 가변 컬렉션이라, 얕게
     * 넘기면 새 폼과 템플릿이 같은 객체를 가리킨다. 그 상태로 폼을 고치면 Hibernate가
     * 두 행에 같은 JSON을 쓴다 — "템플릿에서 폼을 만들어 고쳤더니 템플릿이 함께 바뀐다".
     *
     * FormServiceImpl.duplicate가 원본 폼에 대해 하는 일과 같으며, 실제로 같은
     * QuestionCompositionContent.deepCopy를 부른다.
     */
    public QuestionCompositionContent copyQuestionComposition() {
        return questionComposition.deepCopy();
    }
}
