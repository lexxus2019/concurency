package ru.lab.concurrent.server.engine;

import ru.lab.concurrent.core.dto.ScenarioRequest;
import ru.lab.concurrent.server.config.LabProperties;
import ru.lab.concurrent.server.model.Book;
import ru.lab.concurrent.server.model.Hazard;
import ru.lab.concurrent.server.engine.broken.BrokenEngine;
import ru.lab.concurrent.server.engine.deadlock.DeadlockEngine;
import ru.lab.concurrent.server.engine.limiter.SemaphoreAdmissionEngine;
import ru.lab.concurrent.server.engine.lock.ReadWriteEngine;
import ru.lab.concurrent.server.engine.lock.ShardedTryLockEngine;
import ru.lab.concurrent.server.engine.lock.TwoLevelLockEngine;
import ru.lab.concurrent.server.engine.optimistic.OptimisticCasEngine;
import ru.lab.concurrent.server.engine.queue.QueueActorEngine;
import ru.lab.concurrent.server.engine.sync.GlobalSynchronizedEngine;

import java.util.List;

/** Сборка всех стратегий без Spring — чтобы тесты шли секунды, а не минуты. */
public final class TestEngines {

    private TestEngines() {
    }

    public static LabProperties props(long hazardMicros, int permits) {
        return new LabProperties("none", hazardMicros, 40, 64, false, 8, 512, 2000, permits, false);
    }

    /** Все стратегии, кроме deadlock (он вешает потоки намеренно). */
    public static List<ExchangeEngine> all(Book book, LabProperties props) {
        return List.of(
                new BrokenEngine(book),
                new GlobalSynchronizedEngine(book),
                new ShardedTryLockEngine(book, props),
                new TwoLevelLockEngine(book, props),
                new ReadWriteEngine(book, props),
                new OptimisticCasEngine(book, props),
                new SemaphoreAdmissionEngine(book, props),
                new QueueActorEngine(book, props));
    }

    public static ExchangeEngine deadlock(Book book, LabProperties props) {
        return new DeadlockEngine(book, props);
    }

    public static ScenarioRequest scenario(int buyers, int lots, int stock) {
        return new ScenarioRequest(2, buyers, lots / 2, stock, 100, 100_000);
    }

    public static void hazard(long micros) {
        Hazard.setMicros(micros);
    }
}
