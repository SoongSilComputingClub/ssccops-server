package org.sscc.ssccopsserver.domain.form.controller;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.form.code.FormReceiptStatus;
import org.sscc.ssccopsserver.domain.form.dto.FormDetailResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormDuplicateResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormSaveRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormSaveResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormStatusChangeRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormStatusChangeResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormSummaryResponse;
import org.sscc.ssccopsserver.domain.form.service.FormService;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.authorization.RequireAuthority;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 폼 API (#32). 경로 버전 /v1을 쓰고 컨텍스트 경로에 /api를 두지 않는다 (AP-01).
 * 폼 관리 화면 네 개(목록·상세·편집·복제 버튼)가 전부 이 컨트롤러를 소비한다.
 *
 * 인가는 핸들러마다 갈린다 (#9) — 조회는 FORM_READ, 생성·수정·복제는 FORM_WRITE, 접수 상태
 * 전이는 FORM_STATUS_CHANGE다. 셋 다 시드에서 FORM_MANAGE 아래에 있으므로 '폼 관리'를 통째로
 * 받은 역할은 셋 모두에 닿고, 필요하면 조회만 떼어 줄 수도 있다 — 클래스에 하나로 걸지 않은
 * 이유가 이것이다. **응답자용 PublicFormController에는 권한 요구가 없다**(인증만).
 *
 * 조회를 뺀 쓰기는 전부 @CurrentMember를 받는다. 생성·복제는 폼의 생성자를 서버가 채워야 해서고,
 * 수정은 문항 구성 이력의 변경자를 채워야 해서다(#140 — 그전까지 수정은 남기는 것이 없어 주체를
 * 요구하지 않았다). 상태 전이만 주체를 서비스로 넘기지 않는데, 폼 상태 이력 테이블이 없어 남길
 * 자리가 없기 때문이다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/forms")
public class FormController {

    private final FormService formService;

    /*
     * 폼 목록. 두 필터는 각각 선택이며 둘 다 주면 AND다.
     *
     * **거르는 축은 receiptStatus다** (#325 · ADR-0019). 배지가 그리는 파생값과 같은 값이라
     * '기간 종료' 배지를 보고 그 값으로 거르면 그 폼이 결과에 있다. 저장 컬럼(form_stts_cd)을
     * 그대로 거르던 statusCode는 기간이 끝난 폼을 여전히 OPEN으로 세어 '마감'에 걸지 못했다 —
     * 그것이 운영진이 보고한 증상이다.
     *
     * **statusCode는 남기지 않고 지웠다.** 같은 이름에 파생값을 받게 하는 것은 이슈가 기각했고,
     * 두 축을 나란히 두는 것은 그 기각이 막으려던 질문("이 필터는 어느 쪽인가")을 이름만 바꿔
     * 남기는 것이다. 옛 값은 새 축으로 1:1 번역되지도 않는다 — OPEN 하나가 SCHEDULED·ACCEPTING·
     * EXPIRED 셋으로 갈라져, 셋 중 하나를 고르면 사용자가 보내지 않은 조건을 지어내게 된다.
     * 그래서 웹도 옛 링크(?statusCode=...)를 번역하지 않고 전체로 떨어뜨린다 (ssccops-web#354).
     *
     * 문항 구성(qitemCpstCn)은 응답에 싣지 않는다 — 폼 하나에 문항이 수십 개면 목록 응답이
     * 그만큼 곱해져 비대해진다. 문항이 필요하면 단건 조회를 부른다.
     */
    @Operation(
            summary = "폼 목록 조회",
            description =
                    "폼 관리 화면의 카드 목록. receiptStatus·labelId는 각각 선택이며 둘 다 주면 AND로 걸린다. **필터는 접수 상태"
                        + " 파생값(receiptStatus)으로 거른다** — DRAFT·SCHEDULED·ACCEPTING·EXPIRED·CLOSED"
                        + " 다섯 값이며 응답의 receiptStatus 필드(배지)와 같은 값이라 목록과 배지가 어긋나지 않는다. 접수 기간이 끝난 폼은"
                        + " form_stts_cd가 OPEN인 채로 EXPIRED이므로 CLOSED(운영자가 직접 마감)와 구분된다. 접수 기간이 비어"
                        + " 있는 폼은 '제한 없음'이라 열려 있으면 ACCEPTING이고, 시작 정각·종료 정각은 양쪽 모두 접수 중이다. 저장"
                        + " 컬럼(form_stts_cd)으로 거르던 statusCode 파라미터는 없어졌다. 응답 건수(responseCount)는 제출"
                        + " 이상(SUBMITTED·CHANGES_REQUESTED·ACCEPTED·REJECTED)만 세며 작성 중인 임시저장 응답은 세지"
                        + " 않는다. 목록에는 문항 구성(qitemCpstCn)을 싣지 않는다.")
    @RequireAuthority(AuthorityCode.FORM_READ)
    @GetMapping
    public ApiResponse<List<FormSummaryResponse>> getForms(
            @RequestParam(required = false) FormReceiptStatus receiptStatus,
            @RequestParam(required = false) Long labelId) {
        return ApiResponse.success(formService.getForms(receiptStatus, labelId));
    }

    /*
     * 휴지통 목록 (#329). 지워진 폼만 지운 시각 역순으로 돌려준다.
     *
     * **목록에 필터 값을 하나 더 두지 않고 경로를 나눴다.** receiptStatus에 DELETED를 더하면 그
     * 값이 배지와 같은 어휘라는 #325의 계약이 깨지고(배지는 DELETED를 그리지 않는다), 지운 폼이
     * '전체'에도 섞여 들어온다. 삭제 여부는 운영진이 고르는 축이 아니라 언제나 붙는 조건이다.
     *
     * **요구 권한이 FORM_READ인 것은 이 화면이 목록의 다른 모습이기 때문이다.** 휴지통이
     * 보여주는 것은 폼 목록이 이미 보여주던 값(제목·상태·라벨·응답 수)에 지운 시각 하나가
     * 붙은 것이라, 목록을 볼 수 있는 사람에게 숨길 것이 없다. 되살리는 것은 별개이며 그쪽은
     * FORM_WRITE다 — 읽기와 쓰기를 가르는 이 컨트롤러의 기존 선이 그대로 적용된다.
     *
     * 경로가 /{formId}와 겹치지 않는 것은 리터럴 세그먼트가 경로 변수보다 먼저 매칭되기
     * 때문이다(Spring의 패턴 비교 규칙). formId가 Long이라 'deleted'는 어차피 변환되지 않는다.
     */
    @Operation(
            summary = "삭제된 폼 목록 조회",
            description =
                    "휴지통 화면. 소프트 삭제된 폼만 지운 시각(delDt) 역순으로 돌려준다. 항목의 모양은 폼 목록과 같고"
                            + " delDt만 값이 있다 — 살아 있는 폼의 delDt는 언제나 null이다."
                            + " receiptStatus·labels·responseCount는 지우기 직전 값 그대로이며,"
                            + " responseCount로 '이 폼에 신청이 몇 건 있었는가'를 보고 되살릴지 정한다."
                            + " 되살리기는 POST /v1/forms/{formId}/restore다.")
    @RequireAuthority(AuthorityCode.FORM_READ)
    @GetMapping("/deleted")
    public ApiResponse<List<FormSummaryResponse>> getDeletedForms() {
        return ApiResponse.success(formService.getDeletedForms());
    }

    /*
     * 상대로 시스템 폼이 요구하는 문항(systemRequiredQitemIds)을 싣는다 (#155). 목록에는 없고
     * 상세에만 있는 것은 문항 편집이 이 화면에서만 일어나기 때문이다.
     */
    @Operation(
            summary = "폼 단건 조회",
            description =
                    "폼 상세·편집 화면이 진입 시 호출한다. 문항 구성을 통째로 싣고 있어 편집기가 그대로 초안으로 받아 쓴다."
                            + " systemRequiredQitemIds는 코드가 이 폼에서 반드시 읽는 qitemId 목록이다 — 편집 화면은 이"
                            + " 문항들의 삭제를 미리 잠그면 된다. 시스템 폼이 아니거나 요구 문항이 없으면 빈 배열이며 null은"
                            + " 내려가지 않는다. 미리 잠그는 것은 편의일 뿐이고 지우고 저장하면 서버가 여전히 400"
                            + " SYSTEM_FORM_CONTRACT_VIOLATION으로 거절한다."
                            + " 없는 폼은 404 FORM_NOT_FOUND로 응답한다.")
    @RequireAuthority(AuthorityCode.FORM_READ)
    @GetMapping("/{formId}")
    public ApiResponse<FormDetailResponse> getForm(@PathVariable Long formId) {
        return ApiResponse.success(formService.getForm(formId));
    }

    @Operation(
            summary = "폼 생성",
            description =
                    "생성자(creatrMbrId)는 인증 주체에서 서버가 채우므로 요청 본문에 넣지 않는다."
                            + " 상태를 지정하지 않으면 DRAFT이며, 편집 화면의 '바로 접수 시작'은 OPEN을 보낸다."
                            + " mltplRspnsYn을 true로 두면 한 회원이 이 폼에 여러 건을 낼 수 있다(생략은 false ="
                            + " 회원당 1건). 지원서·설문은 false 그대로 두고, 스터디 제안처럼 한 사람이 두 개를"
                            + " 내는 것이 정상인 폼에만 켠다."
                            + " 문항 구성이 규칙을 어기면 400 INVALID_QUESTION_COMPOSITION,"
                            + " 접수 종료가 시작보다 빠르면 400 INVALID_RECEIPT_PERIOD로 응답한다.")
    @RequireAuthority(AuthorityCode.FORM_WRITE)
    @PostMapping
    public ResponseEntity<ApiResponse<FormSaveResponse>> createForm(
            @Valid @RequestBody FormSaveRequest request, @CurrentMember MemberEntity creator) {
        FormSaveResponse response = formService.createForm(request, creator);
        URI location = URI.create("/v1/forms/" + response.formId());
        return ResponseEntity.created(location).body(ApiResponse.created(response));
    }

    /*
     * 폼 수정. 문항 구성은 부분 갱신이 아니라 전체 교체라 PATCH가 아니라 PUT이다 (AP-06) —
     * 편집 자동 저장(ssccops #63)도 같은 엔드포인트를 그대로 쓴다.
     *
     * 본문의 formSttsCd는 무시한다 (#33). 자동 저장이 상세 응답을 그대로 되돌려 보내므로 그 값을
     * 받아 쓰면 타이핑 한 번이 접수 상태를 덮어쓴다 — 상태는 액션 경로에서만 바뀐다.
     *
     * @CurrentMember를 받는 것은 #140부터다. 문항 구성이 실제로 바뀐 저장은 form_qitem_hstry에
     * 한 행을 남기는데 그 변경자가 여기서 온다 — 요청 본문으로 받으면 남의 이름으로 이력을
     * 쓸 수 있어 이력이 증거가 되지 못한다 (#78이 세운 규칙과 같다).
     */
    @Operation(
            summary = "폼 수정",
            description =
                    "문항 구성과 라벨 지정을 통째로 교체한다. 상태(formSttsCd)는 이 API로 바꿀 수 없고"
                            + " 본문에 실려 와도 무시한다 — POST /v1/forms/{formId}/status를 쓴다."
                            + " labelIds를 생략하거나 빈 배열로 보내면 라벨을 모두 뗀다."
                            + " 이미 응답이 있는 폼에서 기존 qitemId를 지우거나 바꾸면 409 QUESTION_ITEM_IN_USE로"
                            + " 응답한다 — 응답 내용의 key가 qitemId라 끊기면 과거 응답을 읽을 수 없다."
                            + " 시스템 폼(sysYn = true)에서 코드가 요구하는 qitemId를 지우면 400"
                            + " SYSTEM_FORM_CONTRACT_VIOLATION이며, 문구 수정·문항 추가·순서 변경은 허용한다."
                            + " 문항 구성이 실제로 바뀐 저장에서만 qitemVer가 1 오르고 그 시점 구성이 이력에 남는다"
                            + " — 제목·접수 기간만 바꾼 저장에는 버전이 오르지 않는다."
                            + " **mltplRspnsYn(다중 응답 허용)은 이 API로 바꾼다** — 생략하면 false로 저장되므로"
                            + " 편집 자동 저장은 상세 응답에 실려 온 값을 그대로 함께 보내야 한다. 접수 중에도 바꿀 수"
                            + " 있고, 끄더라도 이미 들어온 응답은 지워지지 않는다(지금부터 새로 낼 수 없다는 뜻이다).")
    @RequireAuthority(AuthorityCode.FORM_WRITE)
    @PutMapping("/{formId}")
    public ApiResponse<FormSaveResponse> updateForm(
            @PathVariable Long formId,
            @Valid @RequestBody FormSaveRequest request,
            @CurrentMember MemberEntity actor) {
        return ApiResponse.success(formService.updateForm(formId, request, actor));
    }

    /*
     * 폼 복제. 원본을 바꾸지 않고 새 폼을 만드는 행위라 201이며 Location은 사본을 가리킨다.
     * 상태를 PUT으로 직접 쓰는 대신 행위 경로를 두는 것과 같은 이유로 /duplicate를 쓴다 (AP-03).
     */
    @Operation(
            summary = "폼 복제",
            description =
                    "제목에 '(복사본)'을 붙이고 상태 DRAFT·접수 일시 초기화로 새 폼을 만든다."
                            + " 문항 구성은 깊은 복사라 사본을 고쳐도 원본이 바뀌지 않는다."
                            + " 응답과 라벨은 승계하지 않으며 생성자는 복제를 수행한 회원이다."
                            + " 다중 응답 허용 여부(mltplRspnsYn)는 승계한다 — 제목·문항 구성과 같은 폼의 설정이다.")
    @RequireAuthority(AuthorityCode.FORM_WRITE)
    @PostMapping("/{formId}/duplicate")
    public ResponseEntity<ApiResponse<FormDuplicateResponse>> duplicateForm(
            @PathVariable Long formId, @CurrentMember MemberEntity creator) {
        FormDuplicateResponse response = formService.duplicateForm(formId, creator);
        URI location = URI.create("/v1/forms/" + response.formId());
        return ResponseEntity.created(location).body(ApiResponse.created(response));
    }

    /*
     * 폼 삭제 (#329 · 소프트 삭제). 목록·조회에서 빠지지만 데이터는 남는다.
     *
     * **요구 권한이 FORM_WRITE인 것은 기존 폼 관리 권한을 그대로 따른 것이다**(이슈가 못 박은
     * 조건). 전용 FORM_DELETE를 새로 만들지 않은 근거는 둘이다.
     *
     *   1. **잠가도 지키는 것이 없다.** FORM_WRITE 보유자는 이미 PUT 하나로 제목을 지우고
     *      문항을 통째로 갈아엎을 수 있어, 그 사람에게서 삭제만 막아 봐야 폼을 못 쓰게 만드는
     *      길이 그대로 남는다. 소프트 삭제는 그중 **유일하게 되돌릴 수 있는** 조작이다.
     *   2. **권한을 하나 더 만드는 것은 시드 마이그레이션을 하나 더 만드는 것이다**(authrt 트리 +
     *      role_authrt_rel). 그 값을 치르는 것은 운영 도메인처럼 "삭제만 따로 떼어 주고 싶다"는
     *      요구가 실제로 있을 때이며(#125의 WORK_DELETE·MEETING_DELETE), 폼에는 그 요구가 없다.
     *
     * **되살리기도 같은 권한이다.** 지울 수 있는 사람이 되돌릴 수 없으면 자기가 저지른 것을
     * 스스로 수습하지 못하고, 삭제를 감당 가능하게 만드는 조건(되돌릴 수 있다)이 권한 배분
     * 하나로 깨진다. 반대로 되살리기만 더 낮은 권한에 열면 지운 폼이 다시 목록에 나타나는 것을
     * 삭제 권한 없는 사람이 할 수 있게 된다.
     *
     * 삭제는 생성이 아니고 돌려줄 표현도 없으므로 204가 아니라 **본문 없는 200**이다 —
     * 모든 응답이 ApiResponse 봉투를 쓰는데 이 하나만 본문이 없으면 웹의 공통 응답 처리가
     * 예외를 하나 갖게 된다 (MeetingController.deleteMeeting과 같은 모양).
     */
    @Operation(
            summary = "폼 삭제",
            description =
                    "소프트 삭제다 — 목록·상세·공개 링크·본인 응답 조회에서 빠지지만 데이터는 남고"
                            + " POST /v1/forms/{formId}/restore로 되살릴 수 있다."
                            + " **응답이 있어도 지워진다** — 응답 수를 보지 않는다."
                            + " 그 대가로 그 폼에 응답한 사람의 '내 신청' 목록에서도 항목이 사라지고"
                            + " 본인 응답 상세는 404가 된다(되살리면 그대로 돌아온다)."
                            + " 지워진 폼은 없는 폼과 같은 404 NOT_FOUND로 응답하며 이는 공개 링크가"
                            + " 존재 여부를 알려주지 않기 위해서다."
                            + " 시스템 폼(sysYn = true)은 409 SYSTEM_FORM_IMMUTABLE,"
                            + " 이미 지워진 폼은 409 ALREADY_DELETED, 없는 폼은 404 NOT_FOUND다."
                            + " 응답·문항 이력·라벨 지정은 아무것도 지우지 않는다.")
    @RequireAuthority(AuthorityCode.FORM_WRITE)
    @DeleteMapping("/{formId}")
    public ApiResponse<Void> deleteForm(@PathVariable Long formId) {
        formService.deleteForm(formId);
        return ApiResponse.successWithNoData();
    }

    /*
     * 폼 되살리기 (#329). 삭제의 역이며 **이 경로가 있다는 것이 삭제를 여는 전제였다**
     * (ssccops#261 결정 코멘트 — 되돌릴 수 없으면 하드 삭제와 다를 것이 없고, 그때는 신청자의
     * 기록이 영영 닫힌다).
     *
     * DELETE의 역이라고 해서 PUT이나 PATCH로 두지 않고 행위 경로를 쓰는 것은 /status·/duplicate와
     * 같은 판단이다 (AP-03). 새 자원이 생기지 않으므로 201이 아니라 200이다.
     */
    @Operation(
            summary = "폼 되살리기",
            description =
                    "소프트 삭제된 폼을 목록으로 되돌린다. 접수 상태(formSttsCd)·접수 기간·문항·라벨·응답은"
                            + " 지울 때 그대로 남아 있으므로 지우기 직전 모습으로 돌아온다 — 접수 중이던 폼은"
                            + " 다시 접수 중이다. 그 폼에 응답한 사람의 '내 신청' 목록과 본인 응답 상세도"
                            + " 함께 돌아온다."
                            + " 지워지지 않은 폼은 409 NOT_DELETED, 없는 폼은 404 NOT_FOUND다."
                            + " 요구 권한은 삭제와 같은 FORM_WRITE다 — 지울 수 있는 사람이 되돌릴 수 없으면"
                            + " 삭제를 감당 가능하게 만드는 조건이 깨진다.")
    @RequireAuthority(AuthorityCode.FORM_WRITE)
    @PostMapping("/{formId}/restore")
    public ApiResponse<Void> restoreForm(@PathVariable Long formId) {
        formService.restoreForm(formId);
        return ApiResponse.successWithNoData();
    }

    /*
     * 폼 접수 상태 전이 (#33). 상세 화면의 '접수 시작 / 마감' 버튼과 편집 화면의 '바로 접수 시작'이
     * 이 하나의 액션 경로를 쓴다. 상태를 PUT /v1/forms/{formId}의 필드로 넘기는 경로는 두지
     * 않는다 (AP-03 · SubWorkController의 전이 경로 선례) — 문항을 고치는 것과 접수를 여는 것은
     * 권한·검증·감사 대상이 다르고, 편집 자동 저장(ssccops #63)이 매 타이핑마다 쏘는 PUT에
     * 상태가 실려 있으면 자동 저장이 접수 상태를 덮어쓴다.
     *
     * 상태 변경은 생성이 아니므로 200이다 (LY-06). 전이 가능 여부와 사전 검증은 도메인이
     * 판단하므로 여기서 분기하지 않는다 (LY-02).
     *
     * actor를 받는 것은 기록하기 위해서가 아니라 @CurrentMember가 미가입 주체를 403
     * SIGNUP_REQUIRED로 끊게 하기 위해서다 — 접수를 열고 닫는 것은 회원만 할 수 있어야 한다.
     * 폼 상태 이력 테이블이 없어 수행자를 남길 자리가 없으므로 서비스로는 넘기지 않는다.
     */
    @Operation(
            summary = "폼 접수 상태 전이",
            description =
                    "action은 OPEN 또는 CLOSE다. DRAFT→OPEN·OPEN→CLOSE·CLOSED→OPEN(마감 철회)만 허용하며"
                            + " 그 밖의 전이는 400 INVALID_FORM_STATUS_TRANSITION으로 응답한다."
                            + " 문항이 0개인 폼을 열려 하면 400 FORM_HAS_NO_QUESTION이다."
                            + " 응답의 receiptStatus는 상태와 접수 기간을 함께 본 파생 값으로,"
                            + " 기간이 끝난 폼은 formSttsCd가 OPEN인 채 EXPIRED가 된다 (자동 마감하지 않는다).")
    @RequireAuthority(AuthorityCode.FORM_STATUS_CHANGE)
    @PostMapping("/{formId}/status")
    public ApiResponse<FormStatusChangeResponse> changeFormStatus(
            @PathVariable Long formId,
            @Valid @RequestBody FormStatusChangeRequest request,
            @CurrentMember MemberEntity actor) {
        return ApiResponse.success(formService.changeStatus(formId, request));
    }
}
