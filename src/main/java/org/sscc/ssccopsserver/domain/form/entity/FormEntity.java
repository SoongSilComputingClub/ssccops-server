package org.sscc.ssccopsserver.domain.form.entity;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.code.FormStatusAction;
import org.sscc.ssccopsserver.domain.form.code.error.FormErrorCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * form(폼) — 지원서·신청서의 마스터. 한 행이 화면 하나가 아니라 폼 하나 전체를 담는다.
 *
 * 문항은 별도 테이블이 아니라 qitem_cpst_cn(JSONB) 한 컬럼에 들어 있다. 폼마다 문항의
 * 개수·유형·검증 규칙이 전부 다르고, 폼을 한 번 그리려면 어차피 문항 전부가 필요해서
 * 정규화해도 매번 전량 조회가 된다 (자세한 근거는 QuestionCompositionContent 주석).
 *
 * 라벨은 여기에 컬럼으로 두지 않고 form_lbl_rel로 N:M 연결한다. 한 폼이 '신규모집'과
 * '2026'을 동시에 달 수 있어야 하고, 라벨 자체는 화면에서 늘어나는 운영 데이터라서다.
 * 컬렉션 연관(@OneToMany)을 열지 않은 것은 폼 목록이 라벨을 폼마다 한 번씩 조회해
 * N+1이 되는 것을 막기 위해서다 — 라벨은 FormLabelRelationRepository로 한 번에 모아 온다.
 *
 * 상태(form_stts_cd)를 바꾸는 길은 changeStatus(FormStatusAction) 하나뿐이다 (#33).
 * update()는 상태를 받지 않는다 — 편집 자동 저장(ssccops #63)이 매 타이핑마다 PUT을 쏘는데
 * 거기에 상태가 실릴 수 있으면 자동 저장 한 번이 접수 상태를 덮어쓴다. setter를 열지 않는 것이
 * 그 통제의 전제다.
 *
 * 시스템 폼(sys_form_cd · sys_yn)과 문항 구성 버전(qitem_ver)은 #140에서 더했다. 셋 다 폼
 * 전체에 두며 시스템 폼에만 두지 않는다 — 시스템 폼 전용 컬럼으로 두면 폼 저장 경로가 두 갈래가
 * 되고, 그것은 이 프로젝트가 계속 피해온 "규칙이 두 벌이 되면 갈린다"에 그대로 해당한다.
 *
 * 다중 응답 허용 여부(mltpl_rspns_yn)는 #143에서 더했다. "한 회원이 이 폼에 몇 건까지 낼 수
 * 있는가"는 응답 쪽 규칙처럼 보이지만 폼마다 다른 값이라 폼이 들고 있어야 한다 — 응답 행에 두면
 * 같은 폼의 응답들이 서로 다른 답을 가질 수 있게 되고, 그때 무엇이 그 폼의 규칙인지 알 수 없다.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "form",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_form_sys_form_cd",
                        columnNames = {"sys_form_cd"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class FormEntity {

    /** 새 폼의 문항 구성 버전. 복제본도 원본의 버전을 승계하지 않고 여기서 다시 시작한다 (#140) */
    private static final int INITIAL_QUESTION_VERSION = 1;

    /** 다중 응답 허용 여부를 지정하지 않고 만드는 폼은 1건 폼이다 (#143) */
    private static final boolean SINGLE_RESPONSE_BY_DEFAULT = false;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "form_id")
    private Long id;

    /*
     * 생성자(mbr.mbr_id). 인증 주체를 서버가 기록하며 클라이언트가 지정할 수 없다.
     * 사후 변경 불가라 updatable = false로 잠근다 — 폼을 넘겨받는 개념이 없다.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creatr_mbr_id", nullable = false, updatable = false)
    private MemberEntity creator;

    @Column(name = "form_ttl_nm", nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "form_stts_cd", nullable = false, length = 20)
    private FormStatus status;

    /*
     * 접수 기간. 둘 다 NULL을 허용한다 — 기간 제한 없이 열어 두는 폼이 있고,
     * 작성 중(DRAFT)에는 아직 기간을 정하지 않은 상태가 정상이기 때문이다.
     * 상태(OPEN)와 기간은 별개 축이라 실제 응답 가능 여부는 두 값을 함께 봐야 한다 (#35).
     */
    @Column(name = "rcpt_bgng_dt")
    private Instant receiptBeginAt;

    @Column(name = "rcpt_end_dt")
    private Instant receiptEndAt;

    /*
     * 문항 구성(JSONB). @JdbcTypeCode(SqlTypes.JSON)만으로 Hibernate 6가 방언별 타입을
     * 골라 준다 — PostgreSQL은 jsonb, H2는 JSON이다. columnDefinition에 'jsonb'를 박으면
     * 테스트가 도는 H2에서 DDL이 깨지므로 방언 판단을 가로채지 않는다.
     *
     * 직렬화는 Hibernate가 Jackson으로 처리한다(클래스패스에 있으면 자동 선택). 역직렬화가
     * 깨졌을 때 500이 아니라 도메인 오류로 내리는 변환은 JsonFormatMapperConfig가 맡는다.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "qitem_cpst_cn", nullable = false)
    private QuestionCompositionContent questionComposition;

    /*
     * 시스템 폼 코드 (#140). **코드가 폼을 찾는 열쇠는 이 값이고 form_id도 제목도 아니다.**
     *
     * form_id는 IDENTITY라 환경마다 다르고 제목·라벨은 화면에서 바뀌는 운영 데이터다. 같은
     * 실수를 이미 했다 — 승인자 판정이 역할'명'을 보던 동안 '총무'를 '재무'로 개명하는 것만으로
     * 승인자가 사라졌다(#118 → #123). 그래서 코드가 가리키는 이름을 따로 둔다.
     *
     * NULL을 허용하며 NULL이 평범한 운영 폼이다. UNIQUE를 걸어도 PostgreSQL·H2 모두 NULL은
     * 여러 개를 허용하므로 "코드가 가리키는 폼은 환경당 하나"만 강제된다.
     */
    @Column(name = "sys_form_cd", length = 50)
    private String systemFormCode;

    /*
     * 잠금 대상 표시 (#140). authrt.sys_yn 선례를 그대로 따른다 (BR-M33) — 삭제와 계약 위반만
     * 막고 제목·접수 기간·라벨·상태 전이는 열어 둔다. 운영진이 회차마다 바꾸는 값이라
     * 잠그면 시스템 폼은 한 번 세운 뒤 아무도 운영할 수 없는 폼이 된다.
     *
     * sys_form_cd가 있으면 참인 값이라 컬럼을 따로 둘 필요가 없어 보이지만, 그렇게 두면
     * "코드가 가리키는가"와 "잠겨 있는가"가 한 값에 묶여 나중에 한쪽만 풀 수 없다. 권한 쪽도
     * 코드(PK)와 sys_yn을 따로 들고 있고 같은 이유다.
     *
     * @ColumnDefault를 붙인 것은 ddl-auto: update 때문이다. 이미 행이 있는 dev·prod의 form에
     * DEFAULT 없는 NOT NULL 컬럼을 붙이면 ALTER 자체가 실패하고, Hibernate는 그 실패를 경고로만
     * 남긴 채 부팅해 첫 요청에서 "컬럼 없음"으로 터진다.
     */
    @ColumnDefault("false")
    @Column(name = "sys_yn", nullable = false)
    private Boolean systemDefined;

    /*
     * 문항 구성 버전 (#140). 구성이 실제로 바뀔 때만 오른다 — 근거는 update() 주석에 있다.
     *
     * 응답(form_rspns_hstry.qitem_ver)이 이 값을 찍어 두므로 "이 답은 어느 구성에 대한 답인가"를
     * 나중에 되짚을 수 있다. 옛 버전 구성으로 다시 렌더하는 것은 이번 범위가 아니다.
     */
    @ColumnDefault("1")
    @Column(name = "qitem_ver", nullable = false)
    private Integer questionVersion;

    /*
     * 다중 응답 허용 여부 (#143). 참이면 한 회원이 이 폼에 여러 건을 낼 수 있다.
     *
     * **판정을 폼의 성격(제목·라벨·시스템 폼 여부)에서 유추하지 않고 컬럼 하나로 둔다.** 스터디
     * 제안처럼 한 사람이 두 개를 내는 것이 정상인 폼과 지원서처럼 1건이어야 하는 폼은 겉모습이
     * 같다 — 유추하려면 "제목에 '제안'이 들어가면"이나 "시스템 폼이면" 같은 규칙을 만들어야 하고,
     * 그런 규칙은 운영자가 제목을 고치는 순간 조용히 뜻이 바뀐다(#118의 역할'명' 판정과 같은
     * 종류의 실수다).
     *
     * 기본값이 false인 것은 지금 있는 폼이 전부 1건 폼이기 때문이며, 이 값을 켜는 것은 폼
     * 생성·수정에서 운영자가 직접 하는 선택이다.
     *
     * @ColumnDefault는 sys_yn·qitem_ver와 같은 이유다 — 이미 행이 있는 dev·prod에 DEFAULT 없는
     * NOT NULL 컬럼을 붙이면 ALTER 자체가 실패하고, Hibernate는 그 실패를 경고로만 남긴 채
     * 부팅해 첫 요청에서 "컬럼 없음"으로 터진다.
     */
    @ColumnDefault("false")
    @Column(name = "mltpl_rspns_yn", nullable = false)
    private Boolean multipleResponseAllowed;

    @CreatedDate
    @Column(name = "crt_dt", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "mdfcn_dt", nullable = false)
    private Instant updatedAt;

    /*
     * 폼 생성 팩토리. 상태는 항상 DRAFT다 — 만들자마자 공개되는 경로를 두지 않는다.
     * 데이터사전의 form_stts_cd 기본값 DRAFT를 DB가 아니라 여기서 확정하는 것은,
     * 기본값을 DB에만 두면 엔티티를 읽기 전까지 상태가 NULL로 보이기 때문이다.
     */
    public static FormEntity create(
            MemberEntity creator,
            String title,
            QuestionCompositionContent questionComposition,
            Instant receiptBeginAt,
            Instant receiptEndAt) {
        return create(
                creator,
                title,
                questionComposition,
                receiptBeginAt,
                receiptEndAt,
                FormStatus.DRAFT);
    }

    /*
     * 상태를 지정해 만드는 생성 팩토리 (#32). 폼 편집 화면의 '바로 접수 시작'이 만들자마자
     * OPEN인 폼을 요구해서 열어 둔다 — 만들고 나서 상태를 한 번 더 바꾸게 하면 두 번째 호출이
     * 실패했을 때 사용자가 의도하지 않은 DRAFT 폼이 남는다.
     *
     * OPEN으로 만드는 경우에는 상태 전이 API(#33)가 거는 사전 검증을 똑같이 태운다. 검증을
     * 전이 경로에만 두면 '바로 접수 시작'으로 만든 폼만 문항 0개로 공개되는 구멍이 남는다 —
     * 같은 결과(열린 폼)에 도달하는 두 경로가 다른 규칙을 쓰면 안 된다.
     *
     * 새로 만들어지는 폼은 언제나 시스템 폼이 아니다 (#140). 복제(FormServiceImpl.duplicate)가
     * 이 팩토리를 그대로 쓰므로 시스템 폼의 사본도 자동으로 sys_form_cd = null · sys_yn = false다 —
     * 사본이 또 시스템 폼이면 UNIQUE에 걸리고, 걸리지 않더라도 코드가 어느 쪽을 가리키는지 알 수
     * 없게 된다. 복제 쪽에 해제 코드를 따로 적지 않은 것은 그 규칙이 두 벌이 되지 않게 하기
     * 위해서다 — 시스템 폼을 세우는 자리는 designateAsSystemForm 하나뿐이어야 한다.
     */
    public static FormEntity create(
            MemberEntity creator,
            String title,
            QuestionCompositionContent questionComposition,
            Instant receiptBeginAt,
            Instant receiptEndAt,
            FormStatus status) {
        return create(
                creator,
                title,
                questionComposition,
                receiptBeginAt,
                receiptEndAt,
                status,
                SINGLE_RESPONSE_BY_DEFAULT);
    }

    /*
     * 다중 응답 허용 여부까지 지정하는 생성 팩토리 (#143).
     *
     * 인자를 더하는 대신 생성 뒤에 켜는 메서드를 두지 않은 것은, 그렇게 두면 "만들었지만 아직
     * 값을 정하지 않은 폼"이 잠깐 존재하고 새 생성 경로가 생길 때마다 그 한 줄을 다시 적어야
     * 하기 때문이다 (designateAsSystemForm과 갈리는 지점 — 그쪽은 코드가 세우는 예외적 조작이라
     * 오히려 자리를 하나로 좁혀야 했다).
     *
     * 값을 넘기지 않는 기존 팩토리는 언제나 단일 응답 폼을 만든다. 템플릿에서 나온 폼(#142)·
     * 문항 0개 DRAFT(#133)가 그 경로이며, 템플릿은 이 값을 들고 있지 않으므로 승계할 것도 없다.
     */
    public static FormEntity create(
            MemberEntity creator,
            String title,
            QuestionCompositionContent questionComposition,
            Instant receiptBeginAt,
            Instant receiptEndAt,
            FormStatus status,
            boolean multipleResponseAllowed) {
        FormEntity form =
                new FormEntity(
                        null,
                        creator,
                        title,
                        status,
                        receiptBeginAt,
                        receiptEndAt,
                        questionComposition,
                        null,
                        false,
                        INITIAL_QUESTION_VERSION,
                        multipleResponseAllowed,
                        null,
                        null);
        if (status == FormStatus.OPEN) {
            form.requireOpenable();
        }
        return form;
    }

    /*
     * 폼 수정 (#32 · PUT). 문항 구성은 부분 갱신이 아니라 통째로 교체한다 —
     * QuestionCompositionContent가 모르는 필드를 보존하지 못하므로(ignoreUnknown) 병합하면
     * 클라이언트가 보내지 않은 항목이 조용히 사라진 채 반쯤 남는다.
     *
     * 생성자(creator)는 바꾸지 않는다. 폼을 넘겨받는 개념이 없어 컬럼 자체가 updatable = false다.
     *
     * 상태(form_stts_cd)도 받지 않는다 (#33). 편집 자동 저장이 매 타이핑마다 이 경로를 타는데,
     * 상태를 함께 쓸 수 있으면 자동 저장이 접수 상태를 조용히 덮어쓴다. 상태를 바꾸는 길은
     * changeStatus 하나로 좁힌다 — 문항을 고치는 것과 접수를 여는 것은 권한·검증·감사 대상이
     * 다른 행위다.
     *
     * **문항 구성이 실제로 바뀐 저장에서만 qitem_ver를 올린다** (#140). 무조건 올리면 편집 자동
     * 저장(ssccops #63)이 매 타이핑마다 PUT을 쏘므로 제목 한 글자를 고치는 동안 버전이 수백까지
     * 뛰고, 그만큼의 이력 행이 쌓여 "무엇이 언제 바뀌었는가"를 되짚는 데 아무 쓸모가 없어진다.
     *
     * 비교는 QuestionCompositionContent의 record equals다. 문항이 record·List·Map으로만 이루어져
     * 있어 구조 비교가 그대로 성립하고, 들어오는 값은 QuestionCompositionValidator가 정규화한
     * 결과라 같은 입력이면 언제나 같은 객체가 된다 — 정규화 전 값과 비교하면 유형에 맞지 않는
     * 잔여 속성이 정리된 것만으로도 버전이 올라간다. JSON 문자열로 비교하지 않는 것은 키 순서나
     * 공백처럼 뜻이 없는 차이가 버전을 올리기 때문이다.
     *
     * 올랐는지를 boolean으로 돌려주는 것은 이력(form_qitem_hstry)을 남길지 호출부가 알아야
     * 하는데, 그 판단을 서비스에서 한 번 더 하면 "구성이 바뀌었는가"라는 같은 규칙이 두 벌이
     * 되기 때문이다 (BR-M28). 버전과 이력은 함께 움직여야 한다.
     *
     * **다중 응답 허용 여부(#143)는 접수 중에도 바꿀 수 있다.** 잠글 근거가 없어서다 — 운영진이
     * 폼을 열고 나서야 "한 사람이 두 개 내도 된다"를 깨닫는 것이 실제로 일어나는 일이고, 잠그면
     * 폼을 새로 만들어 링크를 다시 뿌리는 수밖에 없다. 반대로 켰다 끄는 것도 **이미 들어온 응답을
     * 지우지 않는다** — 끄는 것의 뜻은 "지금부터 새로 낼 수 없다"이지 "지난 응답을 무르라"가
     * 아니다(비활성 라벨을 새로 달 수만 없는 것과 같은 축). 그래서 단일 응답 폼인데 한 회원의
     * 응답이 여러 건 남아 있는 상태가 정상적으로 존재할 수 있고, 제출 판정도 그 전제로 쓰였다.
     */
    public boolean update(
            String title,
            QuestionCompositionContent questionComposition,
            Instant receiptBeginAt,
            Instant receiptEndAt,
            boolean multipleResponseAllowed) {
        boolean compositionChanged = !Objects.equals(this.questionComposition, questionComposition);

        this.title = title;
        this.questionComposition = questionComposition;
        this.receiptBeginAt = receiptBeginAt;
        this.receiptEndAt = receiptEndAt;
        this.multipleResponseAllowed = multipleResponseAllowed;

        if (compositionChanged) {
            this.questionVersion = this.questionVersion + 1;
        }
        return compositionChanged;
    }

    /*
     * 시스템 폼 지정 (#140). 코드가 폼을 가리키기 시작하는 유일한 자리다.
     *
     * 요청 본문으로 받는 길을 두지 않는다 — 화면에서 지정할 수 있으면 운영자가 아무 폼에나
     * 코드를 붙여 코드가 기대하는 문항이 없는 폼을 시스템 폼으로 만들 수 있고, 그 순간
     * 이 잠금 장치는 지키는 것이 없어진다. 시드나 이관 스크립트처럼 코드가 직접 세우는
     * 경로만 이 메서드를 부른다.
     *
     * 지정과 잠금이 한 호출인 것은 authrt와 같은 판단이다 — 코드가 가리키는데 잠기지 않은 폼은
     * 표시만 있고 보호가 없는 상태라, 두 값을 따로 세울 수 있게 두면 반드시 한쪽만 세워진다.
     */
    public void designateAsSystemForm(String systemFormCode) {
        this.systemFormCode = systemFormCode;
        this.systemDefined = true;
    }

    public boolean isSystemForm() {
        return Boolean.TRUE.equals(systemDefined);
    }

    /*
     * 이 폼이 한 회원의 응답을 여러 건 받는가 (#143).
     *
     * Boolean을 그대로 내보내지 않고 원시형으로 좁히는 것은 sys_yn과 같은 이유다 — 컬럼이
     * ddl-auto로 붙는 환경에서 값이 아직 채워지지 않은 행을 만나면 null이 그대로 새어 나가고,
     * 그 null은 판정하는 자리마다 NPE 아니면 조용한 오판이 된다.
     */
    public boolean isMultipleResponseAllowed() {
        return Boolean.TRUE.equals(multipleResponseAllowed);
    }

    /*
     * 삭제 잠금 (#140 · 409 SYSTEM_FORM_IMMUTABLE).
     *
     * **아직 폼 삭제 API가 없다.** 그래서 이 메서드는 지금 아무도 부르지 않는다 — 그럼에도
     * 서비스가 아니라 엔티티에 두는 것은, 삭제 경로가 생길 때 그 경로가 반드시 지나야 하는
     * 자리를 미리 한 곳으로 정해 두기 위해서다. 삭제를 만드는 이슈에서 잠금을 함께 구현하게
     * 두면 그 이슈가 잠금을 잊거나 자기 판정을 새로 적어 규칙이 두 벌이 된다 (BR-M28).
     *
     * 400이 아니라 409인 것은 요청 자체는 올바르고 폼의 성격이 거절 이유이기 때문이다
     * (SYSTEM_AUTHORITY_IMMUTABLE과 같은 판단).
     */
    public void requireDeletable() {
        if (isSystemForm()) {
            throw new GeneralException(FormErrorCode.SYSTEM_FORM_IMMUTABLE);
        }
    }

    /*
     * 코드와 데이터의 계약 검사 (#140 · 400 SYSTEM_FORM_CONTRACT_VIOLATION).
     *
     * 시스템 폼에서 막는 것은 **코드가 요구하는 qitemId가 사라지는 것** 하나뿐이다. 문구 수정·
     * 문항 추가·순서 변경·선택지 변경은 전부 허용한다 — 운영진이 회차마다 손대는 값이고,
     * 잠그면 시스템 폼은 한 번 세운 뒤 아무도 고칠 수 없는 폼이 된다.
     *
     * 응답이 있을 때의 QUESTION_ITEM_IN_USE(409)와 기준이 다르다는 점이 요점이다. 그쪽은
     * "이미 받은 답이 끊긴다"라서 응답이 없으면 자유롭게 지울 수 있지만, 이쪽은 "코드가 그
     * qitemId로 값을 읽는다"라서 응답이 한 건도 없어도 지울 수 없다. 둘은 겹치지 않으며 어느
     * 하나가 다른 하나를 대체하지 않는다.
     */
    public void requireSystemContractKept(
            QuestionCompositionContent next, Set<String> requiredQitemIds) {
        if (!isSystemForm() || requiredQitemIds.isEmpty()) {
            return;
        }
        if (!QuestionCompositionContent.qitemIdsOf(next).containsAll(requiredQitemIds)) {
            throw new GeneralException(FormErrorCode.SYSTEM_FORM_CONTRACT_VIOLATION);
        }
    }

    /*
     * 접수 상태 전이 (#33 · POST /v1/forms/{formId}/status).
     *
     * 전이표는 FormStatusAction이 갖고, 여기서는 그 표를 어겼을 때 무엇으로 거절할지와
     * 여는 쪽에만 걸리는 사전 검증을 맡는다. 검사 순서가 곧 오류의 정확도다 — 전이 자체가
     * 불가능한 요청(이미 열린 폼을 또 여는 등)에 문항 개수 오류를 돌려주면 프론트가
     * 엉뚱한 안내를 띄운다.
     *
     * 전이 이력은 남기지 않는다. 데이터사전에 폼 상태 이력 테이블이 없고, 감사 로그(#8)가
     * 확정되기 전에 여기서 새 테이블을 만들면 나중에 두 벌이 된다.
     */
    public void changeStatus(FormStatusAction action) {
        if (!action.isAllowedFrom(this.status)) {
            throw new GeneralException(FormErrorCode.INVALID_FORM_STATUS_TRANSITION);
        }
        if (action.opensReceipt()) {
            requireOpenable();
        }
        this.status = action.targetStatus();
    }

    /*
     * 접수를 열 수 있는 폼인지. 생성(OPEN 지정)과 전이(OPEN 액션) 두 경로가 함께 쓴다.
     *
     * 문항 0개 자체는 저장 시점에는 정상이다 — 편집을 막 시작한 DRAFT가 그 상태이므로
     * QuestionCompositionValidator는 통과시킨다. 막아야 하는 것은 그 상태로 '공개'되는 것이다.
     */
    private void requireOpenable() {
        if (questionComposition == null
                || questionComposition.qitems() == null
                || questionComposition.qitems().isEmpty()) {
            throw new GeneralException(FormErrorCode.FORM_HAS_NO_QUESTION);
        }
        requireValidReceiptPeriod(receiptBeginAt, receiptEndAt);
    }

    /*
     * 접수 기간만 갱신(#133 학술 활동 모집 시작 오케스트레이션 전용). update()와 달리 제목·문항
     * 구성·라벨을 건드리지 않는다 — 학술 도메인은 모집 기간을 반영해야 할 뿐 자신이 모르는
     * 문항 구성을 덮어쓰면 안 된다. changeStatus(OPEN)보다 먼저 불러야 requireOpenable()의
     * 접수 기간 정합성 검사가 새 기간을 본다.
     */
    public void changeReceiptPeriod(Instant receiptBeginAt, Instant receiptEndAt) {
        requireValidReceiptPeriod(receiptBeginAt, receiptEndAt);
        this.receiptBeginAt = receiptBeginAt;
        this.receiptEndAt = receiptEndAt;
    }

    /*
     * 접수 기간 정합성. 폼 생성·수정(#32)은 아직 엔티티가 없는 값을 검사해야 하므로 static이다 —
     * 같은 규칙을 서비스에 한 벌 더 두면 저장 경로와 전이 경로의 판단이 갈린다.
     *
     * 한쪽만 주어진 경우는 검사 대상이 아니다. 기간 제한 없이 여는 폼이 정상이기 때문이다.
     */
    public static void requireValidReceiptPeriod(Instant receiptBeginAt, Instant receiptEndAt) {
        if (receiptBeginAt != null
                && receiptEndAt != null
                && receiptEndAt.isBefore(receiptBeginAt)) {
            throw new GeneralException(FormErrorCode.INVALID_RECEIPT_PERIOD);
        }
    }
}
