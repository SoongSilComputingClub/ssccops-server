package org.sscc.ssccopsserver.domain.notification.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
import org.sscc.ssccopsserver.domain.form.service.ProposalFormSeed;
import org.sscc.ssccopsserver.domain.notification.code.NotificationType;

/*
 * 폼 응답 검토 알림의 문구와 링크 (#528 · ssccops#453).
 *
 * 제목은 «[승인] {폼 제목}» 꼴 — 하위 업무 알림과 같은 대괄호 접두어다. 내용은 «{폼 제목} · 검토
 * {일시}» 한 줄이고 **검토 의견은 싣지 않는다** — 사람이 쓴 글이라 알림 행·푸시 페이로드에 남기지
 * 않는다(ADR-0024와 같은 축 · #453 «내용에 검토 의견 첫 줄 없음»). 의견은 화면에서 읽는다.
 *
 * 검토 일시는 응답의 mdfcn_dt다 — 검토가 그 값을 갱신하고(reviewResponse의 flush) 이 알림은 그
 * 직후에 만들어지므로 검토 이력 행을 다시 찾지 않는다.
 *
 * 링크는 www의 «내 응답» 허브다. 기획안(`sys_form_cd = PROPOSAL`)만 `/me/proposals`이고 나머지
 * (행사 신청서·지원서·설문)는 `/me/responses`다. 서버가 origin을 모르므로 경로만 싣는다.
 */
final class FormResponseNotificationText {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter REVIEWED_AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(SERVICE_ZONE);

    static final String PROPOSALS_PATH = "/me/proposals";
    static final String RESPONSES_PATH = "/me/responses";

    private FormResponseNotificationText() {}

    static String title(NotificationType type, FormResponseHistoryEntity response) {
        return prefix(type) + " " + response.getForm().getTitle();
    }

    static String body(FormResponseHistoryEntity response) {
        return response.getForm().getTitle() + " · 검토 " + reviewedAtText(response.getUpdatedAt());
    }

    static String linkPath(FormResponseHistoryEntity response) {
        FormEntity form = response.getForm();
        return ProposalFormSeed.SYSTEM_FORM_CODE.equals(form.getSystemFormCode())
                ? PROPOSALS_PATH
                : RESPONSES_PATH;
    }

    private static String prefix(NotificationType type) {
        return switch (type) {
            case RESPONSE_ACCEPTED -> "[승인]";
            case RESPONSE_REJECTED -> "[반려]";
            case RESPONSE_CHANGES_REQUESTED -> "[수정 요청]";
            default -> throw new IllegalArgumentException("not a form response type: " + type);
        };
    }

    private static String reviewedAtText(Instant reviewedAt) {
        return reviewedAt == null ? "-" : REVIEWED_AT.format(reviewedAt);
    }
}
