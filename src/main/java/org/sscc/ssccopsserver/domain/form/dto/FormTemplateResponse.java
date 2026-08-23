package org.sscc.ssccopsserver.domain.form.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.form.entity.FormTemplateEntity;

/*
 * 폼 템플릿 한 건 — 목록 항목이자 생성·수정·사용 여부 전환의 응답 (#142).
 *
 * **qitemCpstCn이 없는 것이 이 DTO의 핵심이다.** 폼 목록(FormSummaryResponse)이 문항 구성을
 * 싣지 않는 것과 같은 규칙이며 이유도 같다 — 템플릿 하나에 문항이 수십 개까지 늘어나는데
 * 목록 카드는 이름·설명·문항 수·수정 일시만 그린다. 문항이 필요하면 단건 조회를 부른다.
 *
 * 저장(POST·PUT) 응답에도 문항 구성을 되돌려주지 않는다 — FormSaveResponse와 같은 판단이다.
 * 방금 보낸 것과 같은 값이라 왕복 비용만 늘고, 서버가 정리한 결과(유형에 맞지 않는 잔여 속성
 * 제거)를 확인해야 한다면 상세 조회가 그 자리다.
 *
 * qitemCnt는 엔티티에 없는 파생 값이지만 별도 조회가 필요 없다 — 문항 구성은 템플릿 행의
 * 한 컬럼이라 이미 메모리에 올라와 있다. 목록에서 구성을 뺀 대신 "이 템플릿이 몇 문항짜리인가"
 * 하나는 남겨야 화면이 빈 템플릿과 완성된 템플릿을 구분해 보여줄 수 있다.
 *
 * creatrMbrId·creatrMbrNm을 두 필드로 펴는 것은 FormDetailResponse와 같은 표기다.
 * **접근 제어에 쓰이는 값이 아니다** — 템플릿은 공용이고 이 필드는 "누가 만들었나"를 보여줄 뿐이다.
 *
 * 일시는 AP-12에 따라 Asia/Seoul 오프셋을 포함해 내려준다.
 */
public record FormTemplateResponse(
        Long formTmplId,
        String tmplNm,
        String tmplExpln,
        boolean useYn,
        int qitemCnt,
        Long creatrMbrId,
        String creatrMbrNm,
        OffsetDateTime crtDt,
        OffsetDateTime mdfcnDt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static FormTemplateResponse from(FormTemplateEntity template) {
        return new FormTemplateResponse(
                template.getId(),
                template.getName(),
                template.getDescription(),
                template.isActive(),
                questionCountOf(template),
                template.getCreator().getId(),
                template.getCreator().getName(),
                toOffsetDateTime(template.getCreatedAt()),
                toOffsetDateTime(template.getUpdatedAt()));
    }

    /*
     * 문항 수. qitems가 NULL인 구성은 검증기를 통과한 적이 없지만(validate가 빈 목록으로
     * 굳힌다), 이 record는 저장 직후가 아닌 조회 경로에서도 쓰이므로 NULL을 0으로 읽는다 —
     * 목록 한 건 때문에 화면 전체가 500이 되는 것보다 낫다.
     */
    static int questionCountOf(FormTemplateEntity template) {
        if (template.getQuestionComposition() == null
                || template.getQuestionComposition().qitems() == null) {
            return 0;
        }
        return template.getQuestionComposition().qitems().size();
    }

    static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
