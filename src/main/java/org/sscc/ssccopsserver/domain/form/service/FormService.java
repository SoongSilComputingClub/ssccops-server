package org.sscc.ssccopsserver.domain.form.service;

import java.time.Instant;
import java.util.List;

import org.sscc.ssccopsserver.domain.form.code.FormReceiptStatus;
import org.sscc.ssccopsserver.domain.form.dto.FormDetailResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormDuplicateResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormSaveRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormSaveResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormStatusChangeRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormStatusChangeResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormSummaryResponse;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
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
}
