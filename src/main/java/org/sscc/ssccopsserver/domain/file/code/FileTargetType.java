package org.sscc.ssccopsserver.domain.file.code;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/*
 * 파일 참조가 붙는 대상의 종류 (file_rfrnc.trgt_se_cd · #220).
 *
 * **이 값과 대상_ID 둘이 소유자를 가리키며 FK는 없다.** 대상 테이블이 여럿이라 걸 수 없고,
 * 그래서 부모가 지워져도 참조 행은 남는다 — 정리는 각 도메인의 책임이다. 원래부터 이 행은
 * 실물을 가리킨다는 보장이 없으므로(서버가 PUT을 관측하지 않는다, FileReferenceEntity 주석)
 * 새로운 종류의 문제가 아니라 이미 감수하던 성질이 하나 느는 것이다.
 *
 * **접두사를 대상이 갖는 것은 그 값이 대상마다 다르기 때문이다.** 키를 만드는 쪽과 옛 URL에서
 * 키를 되돌리는 쪽(FileReferenceEntity.objectKey)이 같은 값을 봐야 하는데, 공통 엔티티에
 * 상수 하나로 두면 대상이 늘어나는 순간 그 상수가 학술 전용이라는 사실이 드러난다.
 *
 * 대상이 늘 때 하는 일은 여기 한 줄과 표준코드 시트 한 줄이다(코드값은 지금 SESSION 하나뿐).
 */
@Getter
@RequiredArgsConstructor
public enum FileTargetType {

    /** 학술 회차 출석 인증사진 (#137). 키는 academic-programs/{활동}/sessions/{회차}/{uuid}.{ext} */
    SESSION("academic-programs/");

    /** 이 대상의 오브젝트 키 접두사 */
    private final String objectKeyPrefix;
}
