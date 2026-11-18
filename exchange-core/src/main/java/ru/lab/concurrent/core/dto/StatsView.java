package ru.lab.concurrent.core.dto;

/**
 * Внутренняя статистика сервиса. То, ради чего вообще стоит замерять лабу:
 * «правильно» и «быстро» — это две отдельные оси, а студенты обычно пытаются
 * решить обе одним {@code synchronized}.
 *
 * @param opsOk         применённых операций
 * @param opsRejected   отклонено бизнес-причинами (нет денег / нет товара)
 * @param opsBusy       отклонено из-за конкуренции: tryLock не взял замок, очередь переполнена
 * @param conflicts     проигравших попыток CAS (оптимистичная блокировка)
 * @param retries       сколько раз оптимистичная стратегия повторила операцию
 * @param lockWaitMicros суммарно время ожидания замка
 * @param maxHoldMicros  самый длинный удержанный критический сегмент
 * @param queueDepth     текущая длина очереди задач (акторная стратегия)
 * @param queueMax       максимальная длина очереди за прогон
 * @param threads        сколько разных потоков заходило в сервис (виден эффект «конвоя»)
 */
public record StatsView(
        String strategy,
        long opsOk,
        long opsRejected,
        long opsBusy,
        long conflicts,
        long retries,
        long lockWaitMicros,
        long maxHoldMicros,
        int queueDepth,
        int queueMax,
        int threads) {

    public double busyShare() {
        long total = opsOk + opsRejected + opsBusy;
        return total == 0 ? 0 : (double) opsBusy / total;
    }
}
