package org.sscc.ssccopsserver.domain.form.service;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.sscc.ssccopsserver.domain.form.code.FormReceiptStatus;
import org.sscc.ssccopsserver.domain.form.dto.FormDetailResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormDuplicateResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormSaveRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormSaveResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormStatusChangeRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormStatusChangeResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormSummaryResponse;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/** 폼 조회·생성·수정·복제(#32)와 접수 상태 전이(#33). 폼 관리 화면이 전부 이 인터페이스를 소비한다. */
public interface FormService {

    /*
     * 목록. 두 필터는 각각 선택이며 둘 다 주면 AND다.
     *
     * 거르는 축은 접수 상태 파생값이다 (#325 · ADR-0019) — 배지와 같은 값이라 '기간 종료'
     * 배지를 보고 그 값으로 거르면 그 폼이 결과에 있다. 저장 컬럼(form_stts_cd)으로 거르던
     * 옛 축은 없어졌다.
     */
    List<FormSummaryResponse> getForms(FormReceiptStatus receiptStatus, Long labelId);

    /*
     * 휴지통 목록 (#329). 지워진 폼만, 지운 시각 역순으로 돌려준다.
     *
     * 목록(getForms)에 필터 값을 하나 더 두지 않고 메서드를 나눈 것은 삭제 여부가 접수 상태와
     * 다른 축이기 때문이다 — 근거는 FormRepository.findAllForAdminList 주석에 있다.
     */
    List<FormSummaryResponse> getDeletedForms();

    FormDetailResponse getForm(Long formId);

    FormSaveResponse createForm(FormSaveRequest request, MemberEntity creator);

    /*
     * 수정. 상태(formSttsCd)는 바꾸지 않는다 — 상태를 바꾸는 길은 changeStatus 하나다 (#33).
     *
     * 수행자(actor)는 문항 구성 이력의 변경자로 쓰인다 (#140). 요청 본문이 아니라
     * @CurrentMember에서 오며, 문항 구성이 실제로 바뀌지 않은 저장에서는 이력 자체가 남지 않아
     * 쓰이지 않는다 — 그래도 인자로 받는 것은 어떤 저장이 버전을 올릴지 컨트롤러가 미리 알 수
     * 없기 때문이다.
     */
    FormSaveResponse updateForm(Long formId, FormSaveRequest request, MemberEntity actor);

    /*
     * 접수 상태 전이 (#33). 전이표는 FormStatusAction이, 전이 가능 여부와 사전 검증은
     * FormEntity가 갖는다.
     *
     * 수행자(actor)를 받지 않는다. 데이터사전에 폼 상태 이력 테이블이 없어 기록할 자리가
     * 없고, 감사 로그(#8)가 확정되기 전에 여기서 새 테이블을 만들면 나중에 두 벌이 된다.
     * 회원 여부 자체는 컨트롤러의 @CurrentMember가 끊는다.
     */
    FormStatusChangeResponse changeStatus(Long formId, FormStatusChangeRequest request);

    /** 복제. 생성자는 원본 생성자가 아니라 복제를 수행한 회원이다 */
    FormDuplicateResponse duplicateForm(Long formId, MemberEntity creator);

    /*
     * 소프트 삭제 (#329 · DELETE /v1/forms/{formId}). del_dt를 채우고 데이터는 남긴다.
     *
     * **응답 수를 보지 않는다** (ssccops#261 결정). 시스템 폼만 거절한다(#140의 잠금).
     * 이미 지워진 폼은 409 FORM_ALREADY_DELETED이며 없는 폼(404)과 구별해 준다.
     */
    void deleteForm(Long formId);

    /*
     * 되살리기 (#329 · POST /v1/forms/{formId}/restore). del_dt를 비운다.
     *
     * **이 경로가 삭제의 전제다.** 되돌릴 수 없으면 응답이 있는 폼을 지우는 결정이 하드 삭제와
     * 같아지고, 그때는 신청자의 기록이 영영 닫힌다 (ssccops#261 결정 코멘트).
     * 지워지지 않은 폼은 409 FORM_NOT_DELETED다.
     */
    void restoreForm(Long formId);

    /*
     * 문항 0개인 DRAFT 폼 생성 (#133 학술 활동 승인 후속 처리 전용, 공개 API 아님).
     *
     * createForm과 갈리는 것은 반환 타입과 호출부다 — 이쪽은 DTO가 아니라 엔티티를 돌려준다.
     * 호출부(AcademicProgramApprovalEffectsServiceImpl)가 같은 트랜잭션에서 그 폼을
     * EventEntity에 즉시 연결해야 하는데, DTO로는 그 연결을 만들 수 없다. qitem_cpst_cn은
     * NOT NULL 컬럼이라 NULL 대신 빈 배열로 채운다.
     */
    FormEntity createEmptyDraft(String title, MemberEntity creator);

    /*
     * 접수 기간만 갱신 (#133 학술 활동 모집 시작 오케스트레이션 전용, 공개 API 아님). 제목·문항
     * 구성·라벨은 건드리지 않는다 — updateForm은 본문 전체를 요구해 이 좁은 용도에 쓰면 학술
     * 도메인이 알지도 못하는 폼 내용을 덮어쓰게 된다.
     */
    void changeReceiptPeriod(Long formId, Instant receiptBeginAt, Instant receiptEndAt);

    /*
     * 문항 구성만 교체 (#483 학술 모집 폼의 리더 편집 전용, 공개 API 아님).
     *
     * changeReceiptPeriod와 **같은 자리**이며 이유도 같다 — updateForm은 본문 전체를 요구해
     * 이 좁은 용도에 쓰면 학술 도메인이 알지도 못하는 폼 내용(제목·라벨·다중 응답)을 매 저장마다
     * 되쓰게 되고, 폼에 필드가 늘면 그 조립이 조용히 낡는다. 여기서 바뀌는 것은 qitem_cpst_cn과
     * (실제로 달라졌을 때) qitem_ver뿐이다.
     *
     * **검증·이력은 updateForm과 같은 경로를 지난다** — 문항 구성 검사 · 응답이 쓰는 qitemId
     * 보호(409 QUESTION_ITEM_IN_USE) · 시스템 폼 계약(400 SYSTEM_FORM_CONTRACT_VIOLATION) ·
     * 버전이 오른 저장만 form_qitem_hstry에 한 행. 규칙을 옮겨 적으면 두 저장 경로가 다른 것을
     * 거절하기 시작한다.
     *
     * **접수 기간·상태는 보지 않는다.** 언제까지 고칠 수 있는가는 폼의 규칙이 아니라 부르는
     * 쪽(학술 모집)의 규칙이라 그쪽이 판정한다 — 여기에 창을 박으면 폼 도메인이 모집 일정을
     * 알아야 한다.
     *
     * 돌려주는 것이 상세 응답인 것은 호출부가 곧바로 화면에 실어야 하기 때문이다. 저장 뒤
     * getForm을 한 번 더 부르게 하면 라벨·응답 요약·계약 문항을 조립하는 자리가 두 번 돈다.
     */
    FormDetailResponse changeQuestionComposition(
            Long formId, QuestionCompositionContent composition, MemberEntity actor);

    /*
     * 폼별 접수 건수(제출 이상) 일괄 집계 (#483 학술 모집 목록의 "지원 N건").
     *
     * 목록 카드마다 세면 그대로 N+1이라 한 번에 모아 온다(DB-13). **무엇을 "접수"로 세는가를
     * 폼 도메인 안에 둔 것이 요점이다** — 폼 목록의 responseCount와 같은 기준(작성 중 제외)을
     * 쓰므로 같은 폼을 두 화면에서 보면 숫자가 같다. 학술이 직접 세면 그 기준이 두 벌이 된다.
     *
     * 응답이 한 건도 없는 폼은 **키 자체가 없다.** 0으로 채우지 않는 것은 "그 폼을 묻지
     * 않았다"와 "0건이다"를 호출부가 가를 수 있어야 해서이며, 0으로 읽을지는 호출부가 정한다
     * (FormResponseCount가 세운 규칙 그대로다).
     */
    Map<Long, Long> countSubmittedResponsesByFormIds(Collection<Long> formIds);
}
