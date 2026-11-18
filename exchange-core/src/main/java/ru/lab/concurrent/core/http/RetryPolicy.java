package ru.lab.concurrent.core.http;

import ru.lab.concurrent.core.dto.Call;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Полка с типовыми стратегиями повторения запроса. Каждая стратегия синхронизации сервера
 * диктует свою политику на клиенте — и это отдельный урок лабы:
 *
 * <ul>
 *   <li>{@code tryLock} → сервер отвечает 503 BUSY, клиент обязан ретраить с <b>backoff</b>;</li>
 *   <li>оптимистичная блокировка → 409 CONFLICT, клиент обязан <b>перечитать</b> состояние
 *       и повторить с новой версией;</li>
 *   <li>{@code synchronized} на всё → клиент просто ждёт в очереди, отказов нет, но latency растёт.</li>
 * </ul>
 */
public record RetryPolicy(int maxAttempts, long baseBackoffMillis, double multiplier, long maxBackoffMillis,
                          boolean rereadBeforeRetry) {

    public static final RetryPolicy NONE = new RetryPolicy(1, 0, 1, 0, false);
    public static final RetryPolicy LAB_DEFAULT = new RetryPolicy(6, 5, 2.0, 250, true);

    /**
     * Выполняет {@code action} до успеха или до исчерпания попыток.
     * Возвращает последний ответ — в том числе терминальный бизнес-отказ.
     */
    public Call run(String opName, java.util.function.Supplier<Call> action, Runnable beforeRetry) {
        Call last = null;
        long backoff = baseBackoffMillis;
        for (int attempt = 1; attempt <= Math.max(1, maxAttempts); attempt++) {
            last = action.get();
            if (last.success()) {
                return last;
            }
            if (last.terminal()) {
                return last;
            }
            if (attempt == maxAttempts) {
                return last;
            }
            if (rereadBeforeRetry && beforeRetry != null) {
                beforeRetry.run();
            }
            sleep(jittered(backoff));
            backoff = Math.min(maxBackoffMillis, (long) (backoff * multiplier));
        }
        return last;
    }

    private long jittered(long backoff) {
        if (backoff <= 0) {
            return 0;
        }
        return ThreadLocalRandom.current().nextLong(backoff / 2 + 1, backoff + 1);
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public String describe() {
        return "attempts=" + maxAttempts + ", backoff=" + baseBackoffMillis + "ms x" + multiplier
                + " (до " + maxBackoffMillis + "ms)" + (rereadBeforeRetry ? ", перечитывать перед повтором" : "");
    }
}
