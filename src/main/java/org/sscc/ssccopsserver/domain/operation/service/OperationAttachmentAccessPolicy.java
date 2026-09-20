package org.sscc.ssccopsserver.domain.operation.service;

import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.service.AuthorityPolicy;
import org.sscc.ssccopsserver.domain.operation.code.error.OperationErrorCode;
import org.sscc.ssccopsserver.domain.operation.entity.OperationEntity;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkRepository;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

/*
 * 첨부의 읽기·쓰기 권한 (#493). 대상이 oper 하나라 컨트롤러의 @RequireAuthority로는 못 가른다 —
 * 종류마다 그 건을 **보는/고치는 사람**의 권한을 그대로 따른다. 새 권한 코드를 만들지 않는다.
 *
 * | 종류 | 읽기(목록·내려받기) | 쓰기(올리기·지우기) |
 * |---|---|---|
 * | WORK | WORK_READ | WORK_MANAGE |
 * | SUB_WORK | WORK_READ | 담당자 또는 WORK_MANAGE (SubWorkOwnershipPolicy — 체크리스트 편집과 같은 선) |
 * | MEETING | MEETING_READ | MEETING_MANAGE |
 *
 * 거절은 컨트롤러 애노테이션과 같은 FORBIDDEN이다(화면이 보기에 같은 거절).
 */
@Component
@RequiredArgsConstructor
public class OperationAttachmentAccessPolicy {

    private final AuthorityPolicy authorityPolicy;
    private final SubWorkOwnershipPolicy subWorkOwnershipPolicy;
    private final SubWorkRepository subWorkRepository;

    public void requireRead(OperationEntity operation, MemberEntity performer) {
        AuthorityCode required =
                switch (operation.getOperationType()) {
                    case MEETING -> AuthorityCode.MEETING_READ;
                    case WORK, SUB_WORK -> AuthorityCode.WORK_READ;
                };
        require(required, performer);
    }

    public void requireWrite(OperationEntity operation, MemberEntity performer) {
        switch (operation.getOperationType()) {
            case MEETING -> require(AuthorityCode.MEETING_MANAGE, performer);
            case WORK -> require(AuthorityCode.WORK_MANAGE, performer);
            case SUB_WORK ->
                    subWorkOwnershipPolicy.requireOwnerOrManager(
                            subWorkRepository
                                    .findByOperationId(operation.getId())
                                    .orElseThrow(
                                            () ->
                                                    new GeneralException(
                                                            OperationErrorCode
                                                                    .OPERATION_NOT_FOUND)),
                            performer);
        }
    }

    private void require(AuthorityCode code, MemberEntity performer) {
        if (performer == null || !authorityPolicy.hasAuthority(performer.getId(), code)) {
            throw new GeneralException(OperationErrorCode.FORBIDDEN);
        }
    }
}
