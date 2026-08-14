package org.sscc.ssccopsserver.domain.operation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.sscc.ssccopsserver.domain.operation.code.error.OperationErrorCode;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkTypeActivationRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkTypeResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkTypeSaveRequest;
import org.sscc.ssccopsserver.domain.operation.entity.AuthorizerRole;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkTypeEntity;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkTypeRepository;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;
import org.sscc.ssccopsserver.global.config.JpaAuditingConfig;
import org.sscc.ssccopsserver.support.SubWorkTypeFixture;

/*
 * 하위 업무 유형 관리 (#43 · OPS-018 · OPS-019).
 *
 * @DataJpaTest는 @Configuration을 걸러내므로 JpaAuditingConfig를 명시적으로 들여온다.
 * 없으면 @CreatedDate가 동작하지 않아 crt_dt NOT NULL 위반으로 저장이 실패한다.
 */
@DataJpaTest
@Import(JpaAuditingConfig.class)
@ActiveProfiles("test")
class SubWorkTypeServiceImplTest {

    @Autowired private SubWorkTypeRepository subWorkTypeRepository;
    @Autowired private TestEntityManager entityManager;

    private SubWorkTypeService subWorkTypeService;

    /*
     * 시드가 sub_work_type_id를 지정하지 않으므로(IDENTITY 시퀀스 충돌 방지) 유형은 이름으로 찾는다.
     * 예산지출은 승인이 필요한 지출 유형, 내부행사는 승인이 필요 없는 유형이다.
     */
    private Long expenditureTypeId;
    private Long approvalFreeTypeId;

    @BeforeEach
    void setUp() {
        subWorkTypeService = new SubWorkTypeServiceImpl(subWorkTypeRepository);
        expenditureTypeId =
                SubWorkTypeFixture.idOf(subWorkTypeRepository, SubWorkTypeFixture.EXPENDITURE);
        approvalFreeTypeId =
                SubWorkTypeFixture.idOf(subWorkTypeRepository, SubWorkTypeFixture.APPROVAL_FREE);
    }

    @Test
    void createSubWorkTypeStartsActiveWithOutOfScopeColumnsDefaulted() {
        SubWorkTypeResponse response =
                subWorkTypeService.createSubWorkType(
                        request(
                                "문서제출",
                                true,
                                AuthorizerRole.PRESIDENT,
                                false,
                                null,
                                List.of("제출처 확인", "사본 보관")));

        assertThat(response.useYn()).isTrue();
        assertThat(response.typeName()).isEqualTo("문서제출");
        assertThat(response.authorizerRoleCode()).isEqualTo("PRESIDENT");
        assertThat(response.completionCheckArticles()).containsExactly("제출처 확인", "사본 보관");

        // 기준 금액·지출 여부는 이 API의 범위 밖이라 화면에서 받지 않는다
        SubWorkTypeEntity saved = entity(response.subWorkTypeId());
        assertThat(saved.isExpenditure()).isFalse();
        assertThat(saved.getCriterionAmount()).isNull();
    }

    /*
     * 화면에서 '승인 여부'를 불필요로 바꿔도 승인자·의사결정 칩은 그대로 남아 함께 실려 온다.
     * 이를 400으로 막으면 화면에서 유형을 저장할 방법이 없어지므로 거절이 아니라 정리한다.
     */
    @Test
    void createSubWorkTypeClearsApprovalPolicyWhenApprovalNotNeeded() {
        SubWorkTypeResponse response =
                subWorkTypeService.createSubWorkType(
                        request("스터디운영2", false, AuthorizerRole.DIRECTOR, true, 3, List.of()));

        assertThat(response.approvalNeeded()).isFalse();
        assertThat(response.authorizerRoleCode()).isNull();
        assertThat(response.minAgreeCountNeeded()).isFalse();
        assertThat(response.minAgreeCount()).isNull();
    }

    @Test
    void createSubWorkTypeRejectsMissingAuthorizerWhenApprovalNeeded() {
        assertThatThrownBy(
                        () ->
                                subWorkTypeService.createSubWorkType(
                                        request("승인자없음", true, null, false, null, List.of())))
                .isInstanceOf(GeneralException.class)
                .hasFieldOrPropertyWithValue(
                        "errorCode", OperationErrorCode.INVALID_APPROVAL_POLICY);
    }

    @Test
    void createSubWorkTypeRejectsQuorumWithoutCount() {
        assertThatThrownBy(
                        () ->
                                subWorkTypeService.createSubWorkType(
                                        request(
                                                "정족수인원없음",
                                                true,
                                                AuthorizerRole.TREASURER,
                                                true,
                                                null,
                                                List.of())))
                .isInstanceOf(GeneralException.class)
                .hasFieldOrPropertyWithValue(
                        "errorCode", OperationErrorCode.INVALID_APPROVAL_POLICY);
    }

    /*
     * 정족수 1은 단독과 다른 설정이다 (POL-007 O-03). 단독은 투표 없이 승인자가 바로 누르고,
     * 정족수 1은 다른 한 명의 찬성이 먼저 있어야 승인자가 누를 수 있다.
     */
    @Test
    void createSubWorkTypeAcceptsQuorumOfOne() {
        SubWorkTypeResponse response =
                subWorkTypeService.createSubWorkType(
                        request("정족수1", true, AuthorizerRole.TREASURER, true, 1, List.of()));

        assertThat(response.minAgreeCountNeeded()).isTrue();
        assertThat(response.minAgreeCount()).isEqualTo(1);
    }

    @Test
    void createSubWorkTypeRejectsDuplicateName() {
        assertThatThrownBy(
                        () ->
                                subWorkTypeService.createSubWorkType(
                                        request(
                                                "예산지출",
                                                true,
                                                AuthorizerRole.TREASURER,
                                                false,
                                                null,
                                                List.of())))
                .isInstanceOf(GeneralException.class)
                .hasFieldOrPropertyWithValue(
                        "errorCode", OperationErrorCode.DUPLICATE_SUB_WORK_TYPE_NAME);
    }

    @Test
    void updateSubWorkTypeRejectsNameTakenByAnotherType() {
        assertThatThrownBy(
                        () ->
                                subWorkTypeService.updateSubWorkType(
                                        approvalFreeTypeId,
                                        request("예산지출", false, null, false, null, List.of())))
                .isInstanceOf(GeneralException.class)
                .hasFieldOrPropertyWithValue(
                        "errorCode", OperationErrorCode.DUPLICATE_SUB_WORK_TYPE_NAME);
    }

    @Test
    void updateSubWorkTypeAllowsKeepingItsOwnName() {
        SubWorkTypeResponse response =
                subWorkTypeService.updateSubWorkType(
                        approvalFreeTypeId,
                        request("내부행사", false, null, false, null, List.of("일시·장소 확정")));

        assertThat(response.typeName()).isEqualTo("내부행사");
        assertThat(response.completionCheckArticles()).containsExactly("일시·장소 확정");
    }

    /*
     * 수정은 폼 전체 저장이라 넘어온 값으로 통째로 덮지만, 화면이 들고 있지 않은 값까지
     * 덮으면 안 된다. 시드된 예산지출이 저장 한 번에 지출 유형이 아니게 되는 것을 막는다.
     */
    @Test
    void updateSubWorkTypePreservesOutOfScopeColumnsAndActivation() {
        subWorkTypeService.changeActivation(
                expenditureTypeId, new SubWorkTypeActivationRequest(false));

        subWorkTypeService.updateSubWorkType(
                expenditureTypeId,
                request("예산지출", true, AuthorizerRole.TREASURER, false, null, List.of("영수증 첨부")));

        SubWorkTypeEntity saved = entity(expenditureTypeId);
        assertThat(saved.isExpenditure()).isTrue();
        assertThat(saved.isActive()).isFalse();
    }

    @Test
    void createSubWorkTypeTrimsCompletionCheckArticles() {
        SubWorkTypeResponse response =
                subWorkTypeService.createSubWorkType(
                        request(
                                "항목정리",
                                false,
                                null,
                                false,
                                null,
                                List.of("  앞뒤 공백  ", "", "   ", "두 번째")));

        assertThat(response.completionCheckArticles()).containsExactly("앞뒤 공백", "두 번째");
    }

    @Test
    void createSubWorkTypeStoresNullWhenAllArticlesBlank() {
        SubWorkTypeResponse response =
                subWorkTypeService.createSubWorkType(
                        request("항목없음", false, null, false, null, List.of("   ")));

        assertThat(response.completionCheckArticles()).isEmpty();
        assertThat(entity(response.subWorkTypeId()).getCompletionCheckArticle()).isNull();
    }

    @Test
    void deactivatedTypeDisappearsOnlyFromActiveList() {
        subWorkTypeService.changeActivation(
                expenditureTypeId, new SubWorkTypeActivationRequest(false));

        assertThat(subWorkTypeService.getSubWorkTypes(null))
                .extracting(SubWorkTypeResponse::subWorkTypeId)
                .contains(expenditureTypeId);
        assertThat(subWorkTypeService.getSubWorkTypes(true))
                .extracting(SubWorkTypeResponse::subWorkTypeId)
                .doesNotContain(expenditureTypeId);
        assertThat(subWorkTypeService.getSubWorkTypes(false))
                .extracting(SubWorkTypeResponse::subWorkTypeId)
                .containsExactly(expenditureTypeId);
    }

    @Test
    void deactivatedTypeCanBeReactivated() {
        subWorkTypeService.changeActivation(
                expenditureTypeId, new SubWorkTypeActivationRequest(false));
        SubWorkTypeResponse response =
                subWorkTypeService.changeActivation(
                        expenditureTypeId, new SubWorkTypeActivationRequest(true));

        assertThat(response.useYn()).isTrue();
    }

    @Test
    void updateSubWorkTypeThrowsWhenTypeMissing() {
        assertThatThrownBy(
                        () ->
                                subWorkTypeService.updateSubWorkType(
                                        999L, request("없음", false, null, false, null, List.of())))
                .isInstanceOf(GeneralException.class)
                .hasFieldOrPropertyWithValue(
                        "errorCode", OperationErrorCode.SUB_WORK_TYPE_NOT_FOUND);
    }

    private SubWorkTypeSaveRequest request(
            String typeName,
            boolean approvalNeeded,
            AuthorizerRole authorizerRole,
            boolean minAgreeCountNeeded,
            Integer minAgreeCount,
            List<String> completionCheckArticles) {
        return new SubWorkTypeSaveRequest(
                typeName,
                approvalNeeded,
                authorizerRole,
                minAgreeCountNeeded,
                minAgreeCount,
                completionCheckArticles);
    }

    private SubWorkTypeEntity entity(Long subWorkTypeId) {
        entityManager.flush();
        entityManager.clear();
        return subWorkTypeRepository.findById(subWorkTypeId).orElseThrow();
    }
}
