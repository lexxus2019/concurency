package ru.lab.concurrent.core.dto;

/**
 * Универсальный результат вызова: и HTTP-код, и расшифровка того, почему операция не прошла.
 *
 * <p>Ключевая идея для студентов: <b>причина отказа определяет поведение клиента</b>.
 * {@code INSUFFICIENT_FUNDS} — ретраить бессмысленно, это бизнес-отказ.
 * {@code BUSY}/{@code CONFLICT}/{@code OVERLOADED} — надо ретраить с backoff, это отказ
 * конкурентности. Клиент, который это путает, либо зациклится, либо потеряет легальные сделки.</p>
 */
public record Call(int httpStatus, boolean ok, String reason, LotView lot) {

    /** Бизнес-успех: операция применена. */
    public boolean success() {
        return ok && httpStatus < 300;
    }

    /** Отказ из-за конкуренции за ресурс — такой запрос можно повторить. */
    public boolean concurrencyRetryable() {
        return !success() && switch (reason == null ? "" : reason) {
            case Reason.BUSY, Reason.CONFLICT, Reason.OVERLOADED, Reason.TIMEOUT, Reason.QUEUE_FULL -> true;
            default -> false;
        };
    }

    /** Отказ «навсегда»: повторять нет смысла. */
    public boolean terminal() {
        return !success() && !concurrencyRetryable();
    }

    /** Стабильные метки причин — часть контракта, менять только вместе с клиентами. */
    public static final class Reason {
        public static final String OK = "OK";
        public static final String INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";
        public static final String OUT_OF_STOCK = "OUT_OF_STOCK";
        public static final String NOT_FOUND = "NOT_FOUND";
        public static final String CANCELLED = "CANCELLED";
        public static final String CONFLICT = "CONFLICT";
        public static final String BUSY = "BUSY";
        public static final String OVERLOADED = "OVERLOADED";
        public static final String QUEUE_FULL = "QUEUE_FULL";
        public static final String TIMEOUT = "TIMEOUT";

        private Reason() {
        }
    }

    public static Call ok(LotView lot) {
        return new Call(200, true, Reason.OK, lot);
    }

    public static Call fail(int status, String reason, LotView lot) {
        return new Call(status, false, reason, lot);
    }
}
