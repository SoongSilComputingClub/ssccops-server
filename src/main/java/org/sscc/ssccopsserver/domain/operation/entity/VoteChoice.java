package org.sscc.ssccopsserver.domain.operation.entity;

/*
 * 정족수 승인 투표의 선택지 (OPS-015).
 *
 * 정의서는 허용값을 한글(찬성|반대|기권)로 적고 있으나 저장·전송 값은 영문 대문자로 둔다
 * (개발지침서 EX-10·LY-15 · OPS-010 transition과 같은 선례). 기준 코드에 없는 값은
 * enum 역직렬화 단계에서 걸려 INVALID_CODE_VALUE(400)가 된다.
 *
 * 기권은 만들지 않았다 — 승인함 화면에 기권 버튼이 없다. 자리는 sub_work_aprv_vote.agre_yn의
 * NULL로 남아 있으므로, 화면에 버튼이 생기면 여기 ABSTAIN을 더하고 agreed를 null로 저장한다.
 */
public enum VoteChoice {
    AGREE, // 찬성 — 정족수에 세는 유일한 값
    DISAGREE; // 반대 — 기록·표시용이며 자동 반려로 이어지지 않는다

    public boolean agreed() {
        return this == AGREE;
    }

    public static VoteChoice of(boolean agreed) {
        return agreed ? AGREE : DISAGREE;
    }
}
