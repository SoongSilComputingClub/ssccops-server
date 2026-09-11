package org.sscc.ssccopsserver.global.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/*
 * 감사 로그 한 줄의 재료 (ssccops#299 · ADR-0024).
 *
 * **개인정보를 실을 자리가 없다.** 대상은 식별자(`targetId`), 바뀐 것은 필드 이름(`changedFields`),
 * 전후 값은 코드값(`before`·`after`)이다 — 등급·상태처럼 코드인 것만 넣는다. 이름·전화·이메일·학번은
 * 이 타입으로 표현할 수 없고, 그것이 1차 방어다(2차는 AuditLogTest가 출력에서 확인한다).
 *
 * `decision`은 승인·반려·투표·거절 사유의 **분류**이고(`APPROVE_COMPLETE`·`«폼 작성자»`), 사람이
 * 적은 사유 문장이 아니다 — 사유 문장에는 이름이 들어올 수 있다.
 */
public record AuditEvent(
        AuditAction action,
        Outcome outcome,
        String targetType,
        String targetId,
        List<String> changedFields,
        String before,
        String after,
        String decision,
        String errorCode,
        String message) {

    public enum Outcome {
        SUCCESS("success"),
        FAILURE("failure");

        private final String code;

        Outcome(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public AuditEvent {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(outcome, "outcome");
        targetType = targetType == null ? action.targetType() : targetType;
        changedFields = changedFields == null ? List.of() : List.copyOf(changedFields);
    }

    public static Builder success(AuditAction action) {
        return new Builder(action, Outcome.SUCCESS);
    }

    public static Builder failure(AuditAction action, String errorCode) {
        return new Builder(action, Outcome.FAILURE).errorCode(errorCode);
    }

    public static final class Builder {
        private final AuditAction action;
        private final Outcome outcome;
        private String targetType;
        private String targetId;
        private final List<String> changedFields = new ArrayList<>();
        private String before;
        private String after;
        private String decision;
        private String errorCode;
        private String message;

        private Builder(AuditAction action, Outcome outcome) {
            this.action = action;
            this.outcome = outcome;
        }

        public Builder target(Object id) {
            this.targetId = id == null ? null : String.valueOf(id);
            return this;
        }

        public Builder target(String type, Object id) {
            this.targetType = type;
            return target(id);
        }

        public Builder changedFields(List<String> fields) {
            this.changedFields.addAll(fields);
            return this;
        }

        public Builder change(Object before, Object after) {
            this.before = before == null ? null : String.valueOf(before);
            this.after = after == null ? null : String.valueOf(after);
            return this;
        }

        /** 전이·게시처럼 «결과 상태»만 뜻이 있을 때. 코드값만 넣는다 */
        public Builder after(Object after) {
            this.after = after == null ? null : String.valueOf(after);
            return this;
        }

        public Builder decision(Object decision) {
            this.decision = decision == null ? null : String.valueOf(decision);
            return this;
        }

        public Builder errorCode(String errorCode) {
            this.errorCode = errorCode;
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public AuditEvent build() {
            return new AuditEvent(
                    action,
                    outcome,
                    targetType,
                    targetId,
                    changedFields,
                    before,
                    after,
                    decision,
                    errorCode,
                    message);
        }
    }
}
