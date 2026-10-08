package org.sscc.ssccopsserver.domain.member.service;

import org.sscc.ssccopsserver.domain.member.dto.MemberRosterExportCondition;
import org.sscc.ssccopsserver.domain.member.dto.MemberRosterFile;

/*
 * 회원명부 내보내기 (#674 · ssccops#598). 규칙은 구현체 주석에 있다.
 */
public interface MemberRosterExportService {

    MemberRosterFile export(MemberRosterExportCondition condition);
}
