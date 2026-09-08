package org.sscc.ssccopsserver.domain.academicprogram.service;

import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramRepository;
import org.sscc.ssccopsserver.domain.share.code.ShareTargetType;
import org.sscc.ssccopsserver.domain.share.service.SharePreview;
import org.sscc.ssccopsserver.domain.share.service.SharePreviewProvider;

import lombok.RequiredArgsConstructor;

/*
 * 학술 프로그램의 공유 미리보기 (ssccops#311 · ADR-0016).
 *
 * 경계는 `SubWorkSharePreviewProvider`·`WorkSharePreviewProvider`와 같다 — 공유 도메인은
 * 학술 활동이 무엇인지 모르고 이 클래스는 토큰이 무엇인지 모른다.
 *
 * ## 제목은 자기 것이 아니라 event의 것이다
 *
 * `acdm_actv`에는 제목 컬럼이 없다. 제목·기간·장소 같은 공통 속성은 1:1로 확장하는
 * `event`가 갖고(`AcademicProgramEntity` 주석 — work가 oper를 확장하는 것과 같은 패턴),
 * 여기에는 커리큘럼·목표 같은 학술 전용 필드만 있다. **업무가 부모 운영 건의 제목을 쓰는 것과
 * 같은 자리다.**
 *
 * ## 요약은 `goal_cn`이고 조립하지 않는다
 *
 * `WorkSharePreviewProvider`가 유형 + 기간을 조립한 것은 업무에 **본문이 없었기 때문**이다.
 * 학술 프로그램에는 활동 목표(`goal_cn`)가 NOT NULL로 있으므로 조립할 이유가 없다 — 사람이
 * 적어 낸 문장이 이미 있는데 서버가 문장을 만들면 그것이 곧 지어낸 문구가 된다.
 *
 * **자르는 이유는 표시가 아니라 새는 양이다**(`SubWorkSharePreviewProvider`와 같은 판단) —
 * 카드에 두 줄 남짓만 보이는데 목표 전체를 익명 경로로 흘려보낼 이유가 없다. 몇 자를 실제로
 * 보여줄지는 카드를 만드는 웹(`@ssccops/share-meta`)의 몫이다.
 *
 * ## 담지 않는 것
 *
 * 모집 상태(`acdm_actv_stts_cd`·연결 폼의 접수 상태) · 정원(`pscp_min_cnt`·`pscp_max_cnt`) ·
 * 수강 인원 · 출석률을 담지 않는다. 전부 시간이 바꾸는 값인데 **카드는 한 번 굳으므로**
 * (ssccops#194 제약 ②) 마감된 뒤에도 모집 중이라 말하는 카드가 방에 남는다. `SharePreview`에
 * 담을 자리 자체가 없는 것이 그 결정을 지키는 방법이다.
 *
 * ## 404로 감출 상태가 없다
 *
 * **`acdm_actv`에는 소프트 삭제가 없다**(`AcademicProgramEntity` 주석 · 설계 결정 #2) — 승인이
 * 곧 생성이라 반려된 기획안은 행 자체가 만들어지지 않고, 만들어진 행을 지우는 유스케이스가
 * 없다. 상태 셋(APPROVED·ONGOING·COMPLETED)은 전부 실재하는 활동이고, 상세 조회
 * (`GET /v1/academic-programs/{id}`)도 "존재하면 항상 조회된다"이므로 미리보기가 상세보다
 * 더 감출 것이 없다 — **미리보기가 감추는 것은 상세가 감추는 것과 같아야 한다.**
 *
 * **event의 게시 상태(`event_stts_cd`)로도 감추지 않는다.** 학술 활동의 event는 이관
 * (ssccops#150)이 만들며 `EventEntity.create`가 언제나 DRAFT로 만든다 — PUBLISHED만 통과시키면
 * 학술 링크가 사실상 전부 404가 되고, 애초에 이 토큰이 여는 것은 공개 행사 상세가 아니라
 * 로그인 뒤에 보는 활동 상세다.
 *
 * 그래서 여기서 빈 Optional이 되는 경우는 **없는 활동 하나**이며, 그때 응답은 폐기된 링크와
 * 같은 404다(`ShareErrorCode.SHARE_LINK_NOT_FOUND`).
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AcademicProgramSharePreviewProvider implements SharePreviewProvider {

    /* 익명에게 내주는 본문의 상한. 근거는 `SubWorkSharePreviewProvider`와 같다 — 새는 양이다 */
    private static final int SUMMARY_LIMIT = 500;

    private final AcademicProgramRepository academicProgramRepository;

    @Override
    public ShareTargetType targetType() {
        return ShareTargetType.ACADEMIC_PROGRAM;
    }

    /*
     * `findById`는 `event`를 함께 끌어오도록 재정의돼 있다(`AcademicProgramRepository`) —
     * 제목이 그 연관에 있으므로 LAZY 그대로면 조회가 한 번 더 나간다.
     */
    @Override
    public Optional<SharePreview> preview(Long targetId) {
        return academicProgramRepository
                .findById(targetId)
                .map(
                        program ->
                                new SharePreview(
                                        program.getEvent().getTitle(), summaryOf(program)));
    }

    /*
     * 활동 목표. NOT NULL이라 실제로는 언제나 값이 있지만 공백만 든 경우를 null로 접는다 —
     * **서버가 대체 문구를 만들지 않는다**(`SubWorkSharePreviewProvider`와 같은 판단). 채워
     * 버리면 "목표가 비었다"와 "서버가 그 문구를 줬다"를 웹이 구별할 수 없다.
     */
    private String summaryOf(AcademicProgramEntity program) {
        String goal = program.getGoalContent();
        if (goal == null || goal.isBlank()) {
            return null;
        }
        String trimmed = goal.strip();
        return trimmed.length() <= SUMMARY_LIMIT ? trimmed : trimmed.substring(0, SUMMARY_LIMIT);
    }
}
