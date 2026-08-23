package org.sscc.ssccopsserver.domain.academicprogram.service;

import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.academicprogram.code.error.AcademicProgramErrorCode;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramTypeActivationRequest;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramTypeResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramTypeSaveRequest;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramTypeEntity;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramTypeRepository;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AcademicProgramTypeServiceImpl implements AcademicProgramTypeService {

    private final AcademicProgramTypeRepository academicProgramTypeRepository;
    private final EntityManager entityManager;

    @Override
    public List<AcademicProgramTypeResponse> getAcademicProgramTypes() {
        return academicProgramTypeRepository.findAllByOrderByDisplayOrderAsc().stream()
                .map(AcademicProgramTypeResponse::from)
                .toList();
    }

    /*
     * save()가 아니라 persist()인 것은 코드가 IDENTITY가 아니라 직접 넣는 PK이기 때문이다.
     * Spring Data의 save()는 식별자가 채워져 있으면 merge()로 도는데, merge는 SELECT 후 행이
     * 있으면 UPDATE로 넘어간다 — 같은 코드를 동시에 만들려는 두 요청 중 진 쪽이 409가 아니라
     * 남의 유형을 조용히 덮어쓰게 된다(AuthorityAdminServiceImpl.createAuthority와 같은 이유).
     */
    @Override
    @Transactional
    public AcademicProgramTypeResponse createAcademicProgramType(
            AcademicProgramTypeSaveRequest request) {
        String code = request.typeCd().trim();
        if (academicProgramTypeRepository.existsById(code)) {
            throw new GeneralException(
                    AcademicProgramErrorCode.ACADEMIC_PROGRAM_TYPE_CODE_DUPLICATED);
        }

        AcademicProgramTypeEntity type =
                AcademicProgramTypeEntity.create(
                        code, request.typeNm().trim(), request.indctSeqno());
        try {
            entityManager.persist(type);
            entityManager.flush();
        } catch (DataIntegrityViolationException | PersistenceException ex) {
            throw new GeneralException(
                    AcademicProgramErrorCode.ACADEMIC_PROGRAM_TYPE_CODE_DUPLICATED);
        }
        return AcademicProgramTypeResponse.from(type);
    }

    // 폼 전체 저장이다. 본문의 typeCd는 쓰지 않는다 — 경로의 값이 유일한 식별자다
    @Override
    @Transactional
    public AcademicProgramTypeResponse updateAcademicProgramType(
            String typeCd, AcademicProgramTypeSaveRequest request) {
        AcademicProgramTypeEntity type = getEntity(typeCd);
        type.update(request.typeNm().trim(), request.indctSeqno());
        return AcademicProgramTypeResponse.from(type);
    }

    @Override
    @Transactional
    public AcademicProgramTypeResponse changeActivation(
            String typeCd, AcademicProgramTypeActivationRequest request) {
        AcademicProgramTypeEntity type = getEntity(typeCd);
        type.changeActivation(request.useYn());
        return AcademicProgramTypeResponse.from(type);
    }

    private AcademicProgramTypeEntity getEntity(String typeCd) {
        return academicProgramTypeRepository
                .findById(typeCd)
                .orElseThrow(
                        () ->
                                new GeneralException(
                                        AcademicProgramErrorCode.ACADEMIC_PROGRAM_TYPE_NOT_FOUND));
    }
}
