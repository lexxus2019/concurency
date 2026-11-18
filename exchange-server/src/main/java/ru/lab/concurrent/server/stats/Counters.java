package ru.lab.concurrent.server.stats;

import ru.lab.concurrent.core.dto.StatsView;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Счётчики одной стратегии. Всё на {@link LongAdder}: сами счётчики не должны вносить
 * искажение в замер (синхронизация на счётчике — это «скрытый глобальный замок»).
 *
 * <p>Смотри на эти числа как на диагностический прибор, а не как на отчёт:
 * {@code busy} растёт → замок слишком грубый или таймаут слишком короткий;
 * {@code conflicts}/{@code retries} → оптимистичная стратегия не справляется с «горячим» лотом;
 * {@code maxHoldMicros} → критическая секция слишком длинная (нельзя держать замок поверх I/O).</p>
 */
public final class Counters {

    private final String strategy;

    private final LongAdder opsOk = new LongAdder();
    private final LongAdder opsRejected = new LongAdder();
    private final LongAdder opsBusy = new LongAdder();
    private final LongAdder conflicts = new LongAdder();
    private final LongAdder retries = new LongAdder();
    private final LongAdder reads = new LongAdder();
    private final LongAdder lockWaitMicros = new LongAdder();
    private final LongAdder holdMicros = new LongAdder();
    private final AtomicLong maxHoldMicros = new AtomicLong();
    private final AtomicInteger queueDepth = new AtomicInteger();
    private final AtomicInteger queueMax = new AtomicInteger();
    private final AtomicInteger maxThreadsSeen = new AtomicInteger();

    /** Имена потоков, заходивших в сервис, — по нему видно, что реально было параллельно. */
    private final Map<String, Boolean> threadNames = new ConcurrentHashMap<>();

    public Counters(String strategy) {
        this.strategy = strategy;
    }

    public void ok() {
        opsOk.increment();
        markThread();
    }

    public void rejected() {
        opsRejected.increment();
        markThread();
    }

    public void busy() {
        opsBusy.increment();
        markThread();
    }

    public void conflict() {
        conflicts.increment();
    }

    public void retry() {
        retries.increment();
    }

    public void read() {
        reads.increment();
        markThread();
    }

    public void lockWait(long micros) {
        lockWaitMicros.add(micros);
    }

    public void hold(long micros) {
        holdMicros.add(micros);
        maxHoldMicros.accumulateAndGet(micros, Math::max);
    }

    public void queueDepth(int depth) {
        queueDepth.set(depth);
        queueMax.accumulateAndGet(depth, Math::max);
    }

    private void markThread() {
        threadNames.put(Thread.currentThread().getName(), Boolean.TRUE);
        maxThreadsSeen.accumulateAndGet(threadNames.size(), Math::max);
    }

    public void resetAll() {
        opsOk.reset();
        opsRejected.reset();
        opsBusy.reset();
        conflicts.reset();
        retries.reset();
        reads.reset();
        lockWaitMicros.reset();
        holdMicros.reset();
        maxHoldMicros.set(0);
        queueDepth.set(0);
        queueMax.set(0);
        threadNames.clear();
        maxThreadsSeen.set(0);
    }

    public StatsView view() {
        return new StatsView(strategy,
                opsOk.sum(),
                opsRejected.sum(),
                opsBusy.sum(),
                conflicts.sum(),
                retries.sum(),
                lockWaitMicros.sum(),
                maxHoldMicros.get(),
                queueDepth.get(),
                queueMax.get(),
                maxThreadsSeen.get());
    }
}
