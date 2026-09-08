package org.sscc.ssccopsserver.domain.operation.entity;

/*
 * 완료 체크리스트 항목에 일어난 변경의 종류 (#307). sub_work_chck_list_hstry.chck_chg_se_cd에
 * 문자열로 저장된다.
 *
 * 체크·해제(cmptn_yn)는 여기에 없다. 그것은 진척 기록이라 하루에도 여러 번 뒤집히고, 남기면
 * 정작 봐야 하는 "항목이 왜 셋뿐인가"가 체크 로그에 묻힌다. 이 이력이 답하는 질문은
 * **완료 조건 자체가 언제 어떻게 달라졌는가** 하나다.
 */
public enum ChecklistChangeType {

    // 항목이 새로 생겼다. 이전 문구가 없다
    ADDED,

    // 문구가 바뀌었다. 이전·이후가 모두 있다
    MODIFIED,

    // 항목이 사라졌다. 이후 문구가 없고, 행 자체는 하드 삭제되어 이 이력만 남는다
    REMOVED
}
