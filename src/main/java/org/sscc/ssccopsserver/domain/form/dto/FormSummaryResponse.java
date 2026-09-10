package org.sscc.ssccopsserver.domain.form.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import org.sscc.ssccopsserver.domain.form.code.FormReceiptStatus;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;

/*
 * 폼 목록 항목 (#32 · GET /v1/forms). 폼 관리 화면의 카드 한 장이 쓰는 값만 담는다.
 *
 * qitemCpstCn이 없는 것이 이 DTO의 핵심이다. 문항은 폼 하나에 수십 개까지 늘어나는데
 * 목록 카드는 제목·상태·기간·라벨·응답 수만 그린다. 상세와 같은 모양으로 내리면 화면에
 * 쓰이지도 않는 JSON이 폼 수만큼 곱해져 목록 응답이 비대해진다 (AP-15의 반대편 — 값이 없어도
 * 필드는 내리되, 쓰지 않는 필드는 애초에 넣지 않는다).
 *
 * responseCount는 제출 이상(SUBMITTED·CHANGES_REQUESTED·ACCEPTED·REJECTED)만 센다. 작성 중인 임시저장(DRAFT)은
 * 아직 응답자가 낸 것이 아니라, 세면 운영진이 보는 "응답 N건"이 실제 접수 건수보다 부풀어
 * 마감 판단을 잘못하게 만든다.
 *
 * receiptStatus는 DB 컬럼이 아니라 formSttsCd와 접수 기간을 함께 본 파생 값이다 (#33 ·
 * FormReceiptPolicy). 접수 기간이 끝난 폼은 자동으로 CLOSED가 되지 않으므로 formSttsCd만
 * 그리면 이미 응답을 받지 않는 폼이 목록에서 계속 '접수 중'으로 보인다. 배치로 상태를
 * 덮어쓰는 대신 이 필드로 나눈다 — 배지 문구는 이 값으로 고른다 (ssccops-web #9).
 *
 * sysFormCd·sysYn·qitemVer는 #140에서 더했다. 목록 카드에도 싣는 것은 시스템 폼임을 상세로
 * 들어가기 전에 알아야 하기 때문이다 — 목록에서 삭제 버튼을 눌러 보고 나서 409를 받는 것과
 * 애초에 잠금 배지를 보는 것은 다르다. 문항 구성(qitemCpstCn)을 빼는 것과 어긋나 보이지만,
 * 그쪽은 폼마다 수십 개로 늘어나는 값이고 이쪽은 세 개의 스칼라다.
 *
 * mltplRspnsYn(#143)도 같은 이유로 목록에 싣는다 — 상세로 들어가기 전에 "여러 건 받는 폼"임을
 * 배지로 그릴 수 있어야 하고, 스칼라 하나라 목록 응답을 키우지 않는다.
 *
 * delDt(#329)는 **살아 있는 폼에서 언제나 null이고 휴지통 목록에서만 값이 있다.** 지운 폼은
 * 목록 질의에서 통째로 빠지므로 두 목록이 한 화면에 섞이지 않으며, 그래서 이 필드 하나로 두
 * 목록이 같은 카드를 그린다 — 휴지통 전용 DTO를 따로 만들면 폼 목록에 필드가 늘 때마다 한쪽만
 * 늘어 두 화면이 갈린다. 빼지 않고 null로 내리는 것은 AP-15(값이 없어도 필드는 내린다)다.
 *
 * 일시는 AP-12에 따라 Asia/Seoul 오프셋을 포함해 내려준다.
 */
public record FormSummaryResponse(
        Long formId,
        String formTtlNm,
        FormStatus formSttsCd,
        FormReceiptStatus receiptStatus,
        OffsetDateTime rcptBgngDt,
        OffsetDateTime rcptEndDt,
        String sysFormCd,
        boolean sysYn,
        int qitemVer,
        boolean mltplRspnsYn,
        List<FormLabelSummaryResponse> labels,
        long responseCount,
        OffsetDateTime mdfcnDt,
        OffsetDateTime delDt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static FormSummaryResponse of(
            FormEntity form,
            FormReceiptStatus receiptStatus,
            List<FormLabelSummaryResponse> labels,
            long responseCount) {
        return new FormSummaryResponse(
                form.getId(),
                form.getTitle(),
                form.getStatus(),
                receiptStatus,
                toOffsetDateTime(form.getReceiptBeginAt()),
                toOffsetDateTime(form.getReceiptEndAt()),
                form.getSystemFormCode(),
                form.isSystemForm(),
                form.getQuestionVersion(),
                form.isMultipleResponseAllowed(),
                labels,
                responseCount,
                toOffsetDateTime(form.getUpdatedAt()),
                toOffsetDateTime(form.getDeletedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
