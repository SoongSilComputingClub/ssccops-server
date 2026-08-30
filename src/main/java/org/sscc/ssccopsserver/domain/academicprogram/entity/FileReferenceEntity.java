package org.sscc.ssccopsserver.domain.academicprogram.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * file_rfrnc(출석 인증사진) — 회차 하나에 붙는 사진 1장 (#137, 학술관리_데이터모델.md §2).
 * 개인별 태깅이 없는 단순 첨부 증거이며, sesn_id UNIQUE가 "회차당 1장"을 DB에서 강제한다.
 *
 * **이 행은 사진이 실제로 올라오기 전에 태어난다.** 서버는 바이트를 만지지 않고 presigned PUT
 * URL을 내주기만 하므로(EventImageServiceImpl 주석과 같은 구조) "올라왔는가"는 서버가 관측할
 * 수 있는 사건이 아니다. 그래서 fileUrl은 "이 주소에 올리기로 한 자리"이고, 클라이언트가
 * 업로드를 중간에 그만두면 어디도 가리키지 않는 참조가 남는다. 확인 콜백(PUT 완료 통보)을 두지
 * 않은 것은 그것 역시 클라이언트의 신고라 신뢰도가 같은데 왕복만 한 번 더 늘기 때문이다 —
 * 화면은 fileUrlAddr을 그려 보고 깨지면 다시 올린다(재업로드가 UPSERT라 언제든 다시 부를 수 있다).
 *
 * 감사 컬럼을 두지 않는 것은 sesn·atndc와 같은 이유다(ERD에 없고, 재업로드가 이력을
 * 남기지 않는 도메인이라 시각 하나가 몇 번째 업로드의 것인지 답할 수 없다).
 */
@Entity
@Table(
        name = "file_rfrnc",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_file_rfrnc_sesn",
                        columnNames = {"sesn_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class FileReferenceEntity {

    /*
     * 오브젝트 키의 접두사 (#137의 키 규칙 `academic-programs/{활동}/sessions/{회차}/{uuid}.{ext}`).
     * 키를 만드는 쪽(SessionFileReferenceServiceImpl)과 옛 URL에서 키를 되돌리는 쪽(objectKey)이
     * 같은 값을 봐야 하므로 여기 둔다.
     */
    public static final String OBJECT_KEY_PREFIX = "academic-programs/";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "file_rfrnc_id")
    private Long id;

    /*
     * 대상 회차. updatable = false로 잠근다 — 재업로드는 같은 회차의 사진을 갈아 끼우는 것이지
     * 이 참조를 다른 회차로 옮기는 일이 아니다(sesn은 재제출이 회차를 옮길 수 있어 잠그지
     * 않은 것과 갈린다).
     */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sesn_id", nullable = false, updatable = false)
    private SessionEntity session;

    /*
     * **오브젝트 키다** (#200에서 뜻이 바뀌었다). 컬럼명은 사전 등재값(#178)이라 그대로 두고
     * 담는 값만 바꿨다 — 새 컬럼을 더하면 같은 사실이 두 벌이 되고, 이름을 바꾸면
     * `ddl-auto: update`가 반영하지 않아 dev·prod에 수동 ALTER가 필요해진다.
     *
     * #137에서는 조립된 공개 URL을 담았다. 화면이 그대로 <img src>에 넣는 문자열이면 웹이
     * 공개 도메인을 알 필요가 없다는 판단이었는데, 그 전제(버킷이 공개다)가 실제로는 성립하지
     * 않았다 — 인증사진은 얼굴이 찍힌 사진이라 URL만 알면 열리는 자리에 둘 값이 아니고, 그래서
     * 읽기가 조회 시점에 발급하는 **서명된 URL**로 바뀌었다(#200). 서명에는 키가 필요하다.
     *
     * 옛 행은 전체 URL이 들어 있으므로 그대로 두고 읽을 때 되돌린다(objectKey 참조) —
     * 마이그레이션 없이 동작한다.
     *
     * 길이는 표준도메인 주소V255를 따른다(#178 사전 등재). #137에서는 200이 아니라 500으로
     * 뒀었는데, 이 값이 사용자가 적어 넣는 주소가 아니라
     * `{공개 도메인}/academic-programs/{id}/sessions/{id}/{uuid}.{ext}`로 조립되는 값이라
     * 공개 도메인이 길어지면 함께 길어진다는 판단이었다. 사전에는 그만한 길이의 주소 도메인이
     * 없어 255로 맞췄다 — 조립된 URL은 경로가 고정 형태라(UUID + 확장자) 공개 도메인이 180자를
     * 넘지 않는 한 들어간다. R2 공개 도메인을 그보다 긴 것으로 바꾸는 날에는 이 컬럼이 먼저
     * 터지므로, 그때는 사전의 주소 도메인부터 늘려야 한다.
     */
    @Column(name = "file_url_addr", nullable = false, length = 255)
    private String fileUrl;

    public static FileReferenceEntity of(SessionEntity session, String fileUrl) {
        return new FileReferenceEntity(null, session, fileUrl);
    }

    /*
     * 서명에 쓸 오브젝트 키 (#200).
     *
     * **저장 값이 두 형태다** — #200 이후에 만들어진 행은 키 그대로이고, 그전 행은
     * `{공개 도메인}/{키}` 꼴의 URL이다. 키가 언제나 이 접두사로 시작하므로 그 위치부터 잘라내면
     * 앞에 무엇이 붙어 있었든(잘못 설정됐던 S3 엔드포인트까지) 같은 키가 나온다 — 그래서 기존
     * 데이터를 옮기지 않아도 된다.
     *
     * 되돌리는 자리를 엔티티에 두는 것은 이 값의 뜻을 아는 것이 이 클래스이기 때문이다. 읽는
     * 서비스마다 잘라 쓰면 접두사 규칙이 호출부 수만큼 복제된다.
     *
     * 접두사가 없는 값은 그대로 돌려준다 — 키로 저장된 행이거나, 규칙을 벗어난 값이다. 후자는
     * 여기서 판단하지 않는다: 서명은 성공하고 R2가 404를 돌려주므로 화면이 다시 올리면 된다
     * (참조가 실제 오브젝트를 가리킨다는 보장은 애초에 없다, 위 주석).
     */
    public String objectKey() {
        int index = fileUrl.indexOf(OBJECT_KEY_PREFIX);
        return index < 0 ? fileUrl : fileUrl.substring(index);
    }

    /*
     * 재업로드(UPSERT, #137 확정). 새 참조를 만들지 않고 주소만 갈아 끼운다 — 회차당 1장이라
     * 행을 하나 더 만들 자리가 없고, 지웠다 넣으면 fileReferenceId가 바뀌어 화면이 들고 있던
     * 식별자가 무효가 된다. 이전 R2 오브젝트는 이 순간 아무도 가리키지 않게 되며, 그 정리는
     * 버킷 수명주기의 몫이다(운영 이슈, 설계 결정 #1).
     */
    public void changeFileUrl(String fileUrl) {
        this.fileUrl = fileUrl;
    }
}
