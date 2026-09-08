package org.sscc.ssccopsserver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.dependencies.SliceRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;

/*
 * 도메인 패키지끼리 서로를 되부르지 않는지 검사한다 (ssccops#242).
 *
 * ── 왜 있는가 ──────────────────────────────────────────────────
 * 순환은 **컴파일되는 코드**다. 빌드가 잡아 주지 않고, 사람이 import를 훑기 전에는 드러나지
 * 않는다. 실제로 2026-09-08 측정에서 5개가 나왔고 그중 둘은 전이 순환이라 파일 하나만 봐서는
 * 보이지도 않았다.
 *
 *   academicprogram → event → academicprogram
 *   academicprogram → form  → academicprogram
 *   member          → operation → member
 *   academicprogram → event → form → academicprogram      (전이)
 *   member          → operation → share → member          (전이)
 *
 * 셋 다 "다른 도메인에 읽기 전용 질의 하나를 묻는" 자리였고, 묻는 쪽이 포트 인터페이스를
 * 선언하고 소유한 쪽이 구현하는 방식으로 끊었다(SystemFormApprovalHook · SharePreviewProvider와
 * 같은 모양). 그 방식이 유지되는지를 이 테스트가 지킨다.
 *
 * ── 왜 도메인 슬라이스만 보는가 ────────────────────────────────
 * global 은 도메인이 공통으로 기대는 층이라(ApiResponse · GeneralException · 인가 애스펙트)
 * 도메인 → global 은 정상이고 그 반대만 문제인데, 그것은 순환 검사가 아니라 층 검사의 몫이다.
 * 여기서는 **도메인 사이의 순환 하나만** 본다 — 검사가 여러 가지를 말하면 실패했을 때 무엇이
 * 깨졌는지가 흐려진다.
 *
 * ── example 도메인 ────────────────────────────────────────────
 * domain/example 은 6계층 구조를 보여주는 참고용 템플릿이고 ssccops#244 가 제거를 판단 중이다.
 * 지금은 대상에 그대로 두었다 — 남든 지워지든 순환을 만들면 안 되는 것은 같고, 예외 목록을
 * 두면 그 목록이 다음 예외의 자리가 된다.
 */
@DisplayName("도메인 패키지에는 순환 의존이 없다")
class DomainCycleTest {

    /*
     * 테스트 클래스는 빼고 본다. 테스트는 여러 도메인을 함께 세우는 것이 정상이라(픽스처가
     * 회원을 만들고 업무를 붙인다) 포함하면 순환이 아닌 것이 순환으로 잡힌다.
     */
    private static final JavaClasses PRODUCTION_CLASSES =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("org.sscc.ssccopsserver.domain");

    /*
     * `domain.(*)` 는 domain 바로 아래 한 단계를 슬라이스로 삼는다 — member · operation · form
     * 처럼 도메인 하나가 슬라이스 하나다. 그 아래(service · repository · entity)는 같은
     * 슬라이스로 묶이므로 도메인 안에서의 상호 참조는 검사 대상이 아니다.
     */
    @Test
    @DisplayName("도메인 슬라이스가 서로를 되부르지 않는다")
    void domainSlicesAreFreeOfCycles() {
        SliceRule rule =
                SlicesRuleDefinition.slices()
                        .matching("org.sscc.ssccopsserver.domain.(*)..")
                        .should()
                        .beFreeOfCycles();

        rule.check(PRODUCTION_CLASSES);
    }
}
