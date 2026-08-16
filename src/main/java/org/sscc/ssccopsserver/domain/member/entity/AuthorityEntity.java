package org.sscc.ssccopsserver.domain.member.entity;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.sscc.ssccopsserver.domain.member.code.error.MemberErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * authrt(권한) — 인가의 근거가 되는 권한 한 건 (#9 · ssccops#69 데이터사전).
 *
 * **단일 부모 트리**다(up_authrt_cd). 자식을 가진 노드가 곧 '묶음 권한'이며 별도의 묶음 개념을
 * 두지 않는다 (BR-M21). 부여는 위에서 아래로만 펼쳐진다 — 상위를 가지면 자손 전부를 가진
 * 것이지만, 자손을 가졌다고 상위가 생기지는 않는다 (BR-M22). 펼침 판정은 이 엔티티가 아니라
 * AuthorityPolicy 한 곳이 한다.
 *
 * PK가 IDENTITY가 아니라 코드 문자열인 것은 코드(@RequireAuthority)가 이 값을 직접 가리키기
 * 때문이다. role처럼 환경마다 값이 달라지는 식별자였다면 애노테이션에 적을 수 없다.
 *
 * sys_yn = true는 "코드가 참조하는 권한"이라는 표시다 (BR-M24). 이름·설명·트리 위치는 화면에서
 * 바꿀 수 있지만 삭제와 코드 변경은 막아야 한다 — 지워지는 순간 그 코드를 요구하는 엔드포인트가
 * 통째로 열리거나 통째로 막히기 때문이다. 코드는 PK라 애초에 바뀌지 않는다.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "authrt")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AuthorityEntity {

    /*
     * 순환된 데이터가 이미 들어가 있어도 조상 탐색이 멈추도록 상한을 둔다. 트리 깊이가 이만큼
     * 깊어질 일은 없으므로, 이 상한에 닿았다는 것 자체가 데이터가 깨졌다는 뜻이다.
     */
    private static final int MAX_ANCESTOR_DEPTH = 64;

    @Id
    @Column(name = "authrt_cd", length = 50)
    private String code;

    @Column(name = "authrt_nm", length = 50, nullable = false)
    private String name;

    /*
     * 상위 권한. NULL이면 최상위다.
     *
     * @ManyToOne 자기참조라 지연 로딩이며, 트리를 통째로 걸어야 하는 펼침 판정은 이 연관을
     * 타고 다니지 않고 링크 목록을 질의 한 번으로 받아 쓴다(AuthorityRepository.findAllLinks).
     * 연관을 타면 권한 수만큼 추가 쿼리가 나간다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "up_authrt_cd")
    private AuthorityEntity parent;

    @Column(name = "authrt_expln", length = 500)
    private String explanation;

    @Column(name = "sys_yn", nullable = false)
    private Boolean systemDefined;

    @Column(name = "indct_seqno", nullable = false)
    private Short displayOrder;

    @CreatedDate
    @Column(name = "crt_dt", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "mdfcn_dt", nullable = false)
    private Instant updatedAt;

    public static AuthorityEntity create(
            String code,
            String name,
            AuthorityEntity parent,
            String explanation,
            boolean systemDefined,
            short displayOrder) {

        AuthorityEntity authority =
                new AuthorityEntity(
                        code, name, null, explanation, systemDefined, displayOrder, null, null);
        authority.changeParent(parent);
        return authority;
    }

    /*
     * 상위 권한 지정. 순환은 여기서 막는다 (BR-M23).
     *
     * 자기 자신을 상위로 두거나, 조상 중에 자신이 있는 노드를 상위로 두면 트리가 고리가 된다.
     * 고리가 생기면 펼침이 영영 끝나지 않거나(방문 표시로 막더라도) 하위 권한 하나가 상위 전부를
     * 부여하게 되어 위→아래 한 방향이라는 규칙 자체가 무너진다. 그래서 데이터가 만들어지는
     * 시점에 거절한다 — 판정 쪽 방어(방문 집합)는 이미 깨진 데이터를 견디기 위한 것이고,
     * 깨진 데이터를 만들지 않는 것은 이쪽 책임이다.
     */
    public void changeParent(AuthorityEntity parent) {
        if (parent == null) {
            this.parent = null;
            return;
        }
        if (wouldCycle(parent)) {
            throw new GeneralException(MemberErrorCode.AUTHORITY_CYCLE_DETECTED);
        }
        this.parent = parent;
    }

    public void updateDescription(String name, String explanation, short displayOrder) {
        this.name = name;
        this.explanation = explanation;
        this.displayOrder = displayOrder;
    }

    public boolean isSystemDefined() {
        return Boolean.TRUE.equals(systemDefined);
    }

    private boolean wouldCycle(AuthorityEntity candidateParent) {
        Set<String> visited = new HashSet<>();
        AuthorityEntity cursor = candidateParent;

        for (int depth = 0; cursor != null && depth < MAX_ANCESTOR_DEPTH; depth++) {
            if (cursor.getCode() != null && cursor.getCode().equals(this.code)) {
                return true;
            }
            // 이미 깨진 데이터(기존 고리)를 타고 올라가다 무한 루프에 빠지지 않게 한다
            if (!visited.add(cursor.getCode())) {
                return true;
            }
            cursor = cursor.getParent();
        }
        return cursor != null;
    }
}
