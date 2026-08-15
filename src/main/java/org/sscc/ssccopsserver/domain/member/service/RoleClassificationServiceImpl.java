package org.sscc.ssccopsserver.domain.member.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.code.error.MemberErrorCode;
import org.sscc.ssccopsserver.domain.member.dto.RoleClassificationCreateRequest;
import org.sscc.ssccopsserver.domain.member.dto.RoleClassificationResponse;
import org.sscc.ssccopsserver.domain.member.dto.RoleClassificationUpdateRequest;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleClassificationEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleClassificationRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.RoleClassificationRoleCount;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

/*
 * 역할 분류 관리의 구현 (#80).
 *
 * 지키는 것은 하나다 — **분류가 사라져도 역할이 갈 곳을 잃지 않는다.** role.role_clsf_cd가
 * NOT NULL FK이므로 소속 역할이 있는 분류는 지울 수 없고, PK인 코드는 애초에 바뀌지 않으며,
 * 시드가 '최고관리자'를 두는 SYSTEM은 이름조차 바꿀 수 없다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RoleClassificationServiceImpl implements RoleClassificationService {

    /*
     * 시스템 역할 분류. data.sql이 '최고관리자' 역할을 여기 두고 그 역할만이 SUPER를 갖는다(#71).
     *
     * enum이 아니라 상수 하나인 것은 의도된 것이다 — 역할 분류는 화면에서 바뀌는 사용자 관리
     * 코드테이블이라 서버 코드에 어휘를 굳히지 않는다(data.sql 주석). 코드가 알아야 하는 값은
     * 보호해야 하는 이 하나뿐이고, 나머지 5종은 서버가 이름조차 알 필요가 없다.
     */
    private static final String SYSTEM_CLASSIFICATION_CODE = "SYSTEM";

    /** indctSeqno를 생략했을 때의 기본 표시 순번. 기존 분류 뒤쪽으로 밀어 둔다 */
    private static final int DEFAULT_DISPLAY_ORDER = 99;

    private final MemberRoleClassificationRepository roleClassificationRepository;
    private final MemberRoleRepository memberRoleRepository;
    private final EntityManager entityManager;

    /*
     * 목록 조회는 쿼리 2회다 — 분류 목록 1 + 소속 역할 수 집계 1. 분류마다 역할을 세면 그대로
     * N+1이 된다 (DB-13, FormLabelServiceImpl.getLabels와 같은 방식).
     *
     * 소속 역할이 없는 분류는 집계 결과에 아예 나오지 않으므로 여기서 0으로 채운다 — 방금 만든
     * 분류가 목록에서 빠지면 만들자마자 화면에서 사라진 것으로 보인다.
     */
    @Override
    public List<RoleClassificationResponse> getClassifications() {
        Map<String, Long> roleCountByCode =
                memberRoleRepository.countRolesByClassification().stream()
                        .collect(
                                Collectors.toMap(
                                        RoleClassificationRoleCount::getRoleClsfCd,
                                        RoleClassificationRoleCount::getRoleCount));

        return roleClassificationRepository.findAllByOrderByDisplayOrderAscCodeAsc().stream()
                .map(
                        classification ->
                                RoleClassificationResponse.of(
                                        classification,
                                        roleCountByCode.getOrDefault(classification.getCode(), 0L)))
                .toList();
    }

    /*
     * 분류 생성. 코드는 요청이 정하며 형식은 DTO의 @Pattern이 이미 걸렀다 (400 VALIDATION_FAILED).
     *
     * save()가 아니라 persist()인 것은 코드가 IDENTITY가 아니라 직접 넣는 PK이기 때문이다.
     * Spring Data의 save()는 식별자가 채워져 있으면 merge()로 도는데, merge는 SELECT 후 행이
     * 있으면 UPDATE로 넘어간다 — 같은 코드를 동시에 만들려는 두 요청 중 진 쪽이 409가 아니라
     * 남의 분류 이름을 조용히 덮어쓰게 된다 (#65 AuthorityAdminServiceImpl과 같은 이유).
     *
     * 갓 만든 분류의 소속 역할 수는 셀 것도 없이 0이다.
     */
    @Override
    @Transactional
    public RoleClassificationResponse createClassification(
            RoleClassificationCreateRequest request) {

        String code = request.roleClsfCd().trim();
        if (roleClassificationRepository.existsById(code)) {
            throw new GeneralException(MemberErrorCode.ROLE_CLASSIFICATION_CODE_DUPLICATED);
        }

        MemberRoleClassificationEntity created =
                MemberRoleClassificationEntity.create(
                        code,
                        request.roleClsfNm().trim(),
                        displayOrderOf(request.indctSeqno(), DEFAULT_DISPLAY_ORDER));

        try {
            entityManager.persist(created);
            entityManager.flush();
        } catch (DataIntegrityViolationException | PersistenceException ex) {
            // 선조회를 나란히 통과한 동시 요청은 제약 위반으로만 드러난다 — 같은 409로 옮긴다
            throw new GeneralException(MemberErrorCode.ROLE_CLASSIFICATION_CODE_DUPLICATED);
        }

        return RoleClassificationResponse.of(created, 0L);
    }

    /*
     * 이름·표시 순번 변경. 코드는 본문에 아예 없으므로 바꿀 길이 없다
     * (RoleClassificationUpdateRequest 주석).
     *
     * SYSTEM은 이름만 잠근다. 표시 순번까지 막지 않는 것은 그 값이 목록에서 몇 번째로 그릴지일
     * 뿐이라 무엇도 깨뜨리지 않기 때문이다 — 반대로 이름은 '시스템이 쓰는 역할을 담는 칸'이라는
     * 표시 그 자체라, 바뀌면 최고관리자가 조직 직책인 것처럼 화면에 선다.
     *
     * 같은 이름을 다시 보내는 것은 이름 변경이 아니다. 화면이 분류 한 벌을 통째로 들고 저장하므로
     * SYSTEM의 순번만 옮기려는 정상 요청에도 현재 이름이 실려 오는데, 그것까지 막으면 SYSTEM은
     * 순서를 영영 바꿀 수 없다 (#34 라벨 지정 교체가 '새로 추가되는 것'에만 use_yn을 거는 것과
     * 같은 판단).
     */
    @Override
    @Transactional
    public RoleClassificationResponse updateClassification(
            String roleClsfCd, RoleClassificationUpdateRequest request) {

        MemberRoleClassificationEntity classification = findClassification(roleClsfCd);
        String name = request.roleClsfNm().trim();

        if (isSystemClassification(classification) && !name.equals(classification.getName())) {
            throw new GeneralException(MemberErrorCode.SYSTEM_ROLE_CLASSIFICATION_IMMUTABLE);
        }

        classification.update(
                name, displayOrderOf(request.indctSeqno(), classification.getDisplayOrder()));

        // 관리 화면이 저장 직후에도 "소속 역할 N건"을 그대로 보여주므로 건수를 다시 실어 준다
        return RoleClassificationResponse.of(
                classification, memberRoleRepository.countByRoleClassification(classification));
    }

    /*
     * 삭제. 두 가지를 차례로 막는다 — SYSTEM 분류(409 SYSTEM_ROLE_CLASSIFICATION_IMMUTABLE),
     * 소속 역할이 있는 분류(409 ROLE_CLASSIFICATION_IN_USE).
     *
     * 소속 역할을 함께 지우거나 다른 분류로 옮기지 않는 것은 의도된 것이다. 그렇게 두면 삭제
     * 한 번으로 조직도가 조용히 바뀐다 — 역할을 먼저 옮기게 해서 무엇이 어디로 가는지 화면에서
     * 보이게 한다 (#65 AUTHORITY_IN_USE와 같은 태도).
     */
    @Override
    @Transactional
    public void deleteClassification(String roleClsfCd) {
        MemberRoleClassificationEntity classification = findClassification(roleClsfCd);

        if (isSystemClassification(classification)) {
            throw new GeneralException(MemberErrorCode.SYSTEM_ROLE_CLASSIFICATION_IMMUTABLE);
        }
        if (memberRoleRepository.existsByRoleClassification(classification)) {
            throw new GeneralException(MemberErrorCode.ROLE_CLASSIFICATION_IN_USE);
        }

        roleClassificationRepository.delete(classification);
    }

    // ------------------------------------------------------------------ 헬퍼

    private MemberRoleClassificationEntity findClassification(String roleClsfCd) {
        return roleClassificationRepository
                .findById(roleClsfCd)
                .orElseThrow(
                        () -> new GeneralException(MemberErrorCode.ROLE_CLASSIFICATION_NOT_FOUND));
    }

    private static boolean isSystemClassification(MemberRoleClassificationEntity classification) {
        return SYSTEM_CLASSIFICATION_CODE.equals(classification.getCode());
    }

    private static int displayOrderOf(Integer requested, Integer fallback) {
        if (requested != null) {
            return requested;
        }
        return fallback == null ? DEFAULT_DISPLAY_ORDER : fallback;
    }
}
