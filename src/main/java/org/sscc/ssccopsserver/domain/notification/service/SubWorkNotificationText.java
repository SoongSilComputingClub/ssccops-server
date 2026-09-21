package org.sscc.ssccopsserver.domain.notification.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.notification.code.NotificationType;
import org.sscc.ssccopsserver.domain.operation.entity.OperationEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;

/*
 * 하위 업무 알림의 문구와 링크 (ssccops#446 · #526).
 *
 * 제목은 «[승인 요청] {하위 업무명}» 꼴 — 종류가 대괄호 접두어 하나로 드러나 목록에서 훑힌다.
 * 내용은 «업무명 · 담당 {이름} · 마감 {날짜}» 한 줄. 두 값 다 만들 때 굳힌다(대상이 나중에 바뀌어도
 * 알림은 그때의 문구다). 담당자 이름이 실리는 것은 알림이 **그 사람에게만** 가기 때문이다 —
 * 결재 권한 보유자에게 가는 승인 요청도 «누구 건인가»가 있어야 승인함에서 찾는다.
 *
 * `linkPath`는 어드민의 실제 라우트 `/operations/sub-works/{id}`다(ssccops-web
 * `apps/admin/src/app/(admin)/operations/sub-works/[subWorkId]`). 서버가 origin을 모르므로
 * 경로만 싣고 서비스워커가 자기 origin을 붙인다. 어드민 라우트가 바뀌면 여기가 함께 바뀐다 —
 * 이미 만들어진 알림 행의 경로는 그대로 남는다(문구와 같은 판단).
 */
final class SubWorkNotificationText {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    private SubWorkNotificationText() {}

    static String title(NotificationType type, SubWorkEntity subWork) {
        return prefix(type) + " " + subWork.getTitle();
    }

    static String body(SubWorkEntity subWork) {
        OperationEntity operation = subWork.getOperation();
        return subWork.getWork().getOperation().getTitle()
                + " · 담당 "
                + operation.getPersonInCharge().getName()
                + " · 마감 "
                + dueDateText(subWork);
    }

    static String linkPath(SubWorkEntity subWork) {
        return "/operations/sub-works/" + subWork.getId();
    }

    /** 마감 날짜(서비스 표준 시간대) — 마감 판정이 일자 단위이므로(DeadlinePolicy) 문구도 날짜다 */
    static LocalDate dueDate(Instant dueAt) {
        return dueAt == null ? null : dueAt.atZone(SERVICE_ZONE).toLocalDate();
    }

    private static String prefix(NotificationType type) {
        return switch (type) {
            case APPROVAL_REQUESTED -> "[승인 요청]";
            case APPROVAL_APPROVED -> "[승인]";
            case APPROVAL_REJECTED -> "[반려]";
            case DEADLINE_DUE -> "[마감 D-1]";
            case DEADLINE_OVERDUE -> "[지연]";
        };
    }

    private static String dueDateText(SubWorkEntity subWork) {
        LocalDate date = dueDate(subWork.getDueAt());
        return date == null ? "없음" : date.toString();
    }
}
