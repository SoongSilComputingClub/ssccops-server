package org.sscc.ssccopsserver.domain.form.dto;

import java.time.OffsetDateTime;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;

/*
 * 폼 생성·수정 공용 요청 (#32 · POST /v1/forms · PUT /v1/forms/{formId}).
 *
 * 두 요청이 같은 DTO를 쓰는 것은 폼 편집 화면이 신규와 수정에 같은 편집기를 쓰기 때문이다 —
 * 화면 하나가 만드는 본문이 하나인데 DTO를 나누면 한쪽에만 필드가 늘어 조용히 어긋난다.
 * 웹의 목 스토어에서도 saveForm 하나가 두 경우를 모두 처리한다.
 *
 * creatrMbrId는 여기에 없다. 생성자는 인증 주체에서 서버가 채우며, 클라이언트가 지정할 수
 * 있게 하면 남의 이름으로 폼을 만들 수 있다.
 *
 * formSttsCd는 생성(POST)에서만 쓰인다 — 편집 화면의 '바로 접수 시작'이 만들자마자 OPEN인 폼을
 * 요구하기 때문이다. 수정(PUT)은 이 값을 무시한다 (#33): 편집 자동 저장(ssccops #63)이 상세
 * 응답을 초안으로 받아 그대로 되돌려 보내므로 본문에는 늘 현재 상태가 실려 있고, 그 값을 받아
 * 쓰면 타이핑 한 번이 접수 상태를 덮어쓴다. 필드를 지우지 않고 남겨 둔 것은 자동 저장이 보내는
 * 본문을 거절하지 않기 위해서다. 상태를 바꾸는 길은 POST /v1/forms/{formId}/status 하나다.
 *
 * 접수 기간 역전을 @AssertTrue로 잡지 않는 것은 의도된 것이다. Bean Validation 실패는 전역
 * 핸들러가 VALIDATION_FAILED(400)로 바꾸는데, 계약표는 이 조건에 INVALID_RECEIPT_PERIOD를
 * 요구한다. 그래서 판단을 서비스로 넘긴다 — 운영 도메인(WorkCreateRequest)과 갈리는 지점이다.
 *
 * qitemCpstCn에 @Valid를 걸지 않은 것도 같은 이유다. 문항 간 상호 규칙(분기 대상 페이지가
 * 실재하는가, 정규식이 컴파일되는가)은 필드 단위 제약으로 표현할 수 없어
 * QuestionCompositionValidator 한 곳에서 검사하고 INVALID_QUESTION_COMPOSITION으로 내린다.
 *
 * mltplRspnsYn(다중 응답 허용, #143)은 생성·수정 양쪽에서 쓰인다. formSttsCd처럼 한쪽에서만
 * 쓰이는 값이 아닌 것은 이것이 상태가 아니라 폼의 설정이기 때문이다 — 접수 상태와 달리 편집
 * 자동 저장이 되돌려 보내는 값과 실제 값이 갈릴 일이 없고(상세 응답이 그대로 싣는다), 운영진이
 * 폼을 연 뒤에 마음을 바꾸는 것도 정상적인 조작이다.
 *
 * 생략(null)은 false다. @NotNull을 걸지 않은 것은 이 필드를 모르는 옛 화면·스크립트의 본문이
 * 통째로 400이 되지 않게 하기 위해서다 — 값을 모른다는 것은 "1건 폼"이라는 뜻이고, 그것이 이
 * 프로젝트의 기존 동작 전부다.
 *
 * 일시는 AP-12에 따라 오프셋을 포함한 RFC 3339 문자열로 주고받는다.
 */
public record FormSaveRequest(
        @NotBlank @Size(max = 200) String formTtlNm,
        FormStatus formSttsCd,
        OffsetDateTime rcptBgngDt,
        OffsetDateTime rcptEndDt,
        @NotNull QuestionCompositionContent qitemCpstCn,
        Boolean mltplRspnsYn,
        List<Long> labelIds) {

    /** 생략은 "1건 폼"이다 — 지금 있는 폼이 전부 그쪽이고, 켜는 것이 명시적인 선택이다 */
    public boolean multipleResponseAllowed() {
        return Boolean.TRUE.equals(mltplRspnsYn);
    }

    /*
     * 라벨 미지정과 "라벨을 전부 떼기"를 구분하지 않는다. PUT은 전체 교체이므로 빈 배열이든
     * 생략이든 결과는 라벨 없는 폼이어야 하고, 생략을 "그대로 두기"로 해석하면 라벨을 떼는
     * 방법이 사라진다.
     */
    public List<Long> labelIdsOrEmpty() {
        return labelIds == null ? List.of() : labelIds;
    }
}
