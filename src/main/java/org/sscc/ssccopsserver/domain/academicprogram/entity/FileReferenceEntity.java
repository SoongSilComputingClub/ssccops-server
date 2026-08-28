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
     * 공개 읽기 주소. 오브젝트 키가 아니라 조립된 URL을 담는 것은 이 값이 곧 화면이 그대로
     * <img src>에 넣는 문자열이기 때문이다 — 웹이 계정 ID·버킷으로 URL을 만들면 공개 도메인을
     * 바꾸는 날 저장된 참조와 새 참조가 갈린다(EventImageUploadResponse 주석과 같은 판단).
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
     * 재업로드(UPSERT, #137 확정). 새 참조를 만들지 않고 주소만 갈아 끼운다 — 회차당 1장이라
     * 행을 하나 더 만들 자리가 없고, 지웠다 넣으면 fileReferenceId가 바뀌어 화면이 들고 있던
     * 식별자가 무효가 된다. 이전 R2 오브젝트는 이 순간 아무도 가리키지 않게 되며, 그 정리는
     * 버킷 수명주기의 몫이다(운영 이슈, 설계 결정 #1).
     */
    public void changeFileUrl(String fileUrl) {
        this.fileUrl = fileUrl;
    }
}
