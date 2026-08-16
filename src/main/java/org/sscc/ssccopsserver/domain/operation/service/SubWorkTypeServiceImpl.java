package org.sscc.ssccopsserver.domain.operation.service;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.operation.code.error.OperationErrorCode;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkTypeActivationRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkTypeResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkTypeSaveRequest;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkTypeEntity;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkTypeRepository;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SubWorkTypeServiceImpl implements SubWorkTypeService {

    private final SubWorkTypeRepository subWorkTypeRepository;

    /*
     * 커서 페이징을 두지 않는다. 유형은 기준 데이터라 건수가 수십 단위이고, 관리 화면도 등록 폼
     * 드롭다운도 전량을 한 번에 그린다. 지금 페이징을 넣으면 화면이 쓰지 않는 계약이 먼저 굳는다.
     */
    @Override
    public List<SubWorkTypeResponse> getSubWorkTypes(Boolean useYn) {
        List<SubWorkTypeEntity> subWorkTypes =
                useYn == null
                        ? subWorkTypeRepository.findAllByOrderByIdAsc()
                        : subWorkTypeRepository.findAllByActiveOrderByIdAsc(useYn);
        return subWorkTypes.stream().map(SubWorkTypeResponse::from).toList();
    }

    @Override
    @Transactional
    public SubWorkTypeResponse createSubWorkType(SubWorkTypeSaveRequest request) {
        String typeName = request.typeName().strip();
        if (subWorkTypeRepository.existsByTypeName(typeName)) {
            throw new GeneralException(OperationErrorCode.DUPLICATE_SUB_WORK_TYPE_NAME);
        }

        SubWorkTypeEntity subWorkType =
                SubWorkTypeEntity.create(
                        typeName,
                        request.approvalNeeded(),
                        request.authorizerRoleCodeName(),
                        request.minAgreeCountNeeded(),
                        request.minAgreeCount(),
                        request.completionCheckArticles());
        return SubWorkTypeResponse.from(save(subWorkType));
    }

    /*
     * 폼 전체 저장이다. 승인 규칙이 바뀌어도 이미 등록된 하위 업무에는 소급되지 않는다 —
     * 하위 업무가 등록 시점에 값을 복사해 가기 때문이며, 화면 하단 안내 문구와 같은 규칙이다.
     */
    @Override
    @Transactional
    public SubWorkTypeResponse updateSubWorkType(
            Long subWorkTypeId, SubWorkTypeSaveRequest request) {
        SubWorkTypeEntity subWorkType = getEntity(subWorkTypeId);

        String typeName = request.typeName().strip();
        if (subWorkTypeRepository.existsByTypeNameAndIdNot(typeName, subWorkTypeId)) {
            throw new GeneralException(OperationErrorCode.DUPLICATE_SUB_WORK_TYPE_NAME);
        }

        subWorkType.update(
                typeName,
                request.approvalNeeded(),
                request.authorizerRoleCodeName(),
                request.minAgreeCountNeeded(),
                request.minAgreeCount(),
                request.completionCheckArticles());
        return SubWorkTypeResponse.from(save(subWorkType));
    }

    /*
     * 유형은 하위 업무가 FK로 참조하므로 지우지 못한다. 삭제 대신 사용 여부를 내리며,
     * 이미 그 유형으로 등록된 하위 업무는 그대로 남는다 (form_lbl.use_yn과 같은 축).
     */
    @Override
    @Transactional
    public SubWorkTypeResponse changeActivation(
            Long subWorkTypeId, SubWorkTypeActivationRequest request) {
        SubWorkTypeEntity subWorkType = getEntity(subWorkTypeId);
        subWorkType.changeActivation(request.useYn());
        return SubWorkTypeResponse.from(subWorkType);
    }

    private SubWorkTypeEntity getEntity(Long subWorkTypeId) {
        return subWorkTypeRepository
                .findById(subWorkTypeId)
                .orElseThrow(
                        () -> new GeneralException(OperationErrorCode.SUB_WORK_TYPE_NOT_FOUND));
    }

    /*
     * 선조회만으로는 동시에 들어온 같은 이름을 막지 못한다. UNIQUE 위반을 여기서 받아
     * 선조회와 같은 409로 옮긴다 — 프론트가 두 경우를 구분할 이유가 없다 (회원가입 #21 선례).
     * flush를 강제하는 것은 트랜잭션이 끝난 뒤 터지면 이 코드로 바꿀 자리가 없기 때문이다.
     */
    private SubWorkTypeEntity save(SubWorkTypeEntity subWorkType) {
        try {
            return subWorkTypeRepository.saveAndFlush(subWorkType);
        } catch (DataIntegrityViolationException e) {
            throw new GeneralException(OperationErrorCode.DUPLICATE_SUB_WORK_TYPE_NAME);
        }
    }
}
