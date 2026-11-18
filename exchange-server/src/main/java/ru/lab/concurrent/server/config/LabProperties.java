package ru.lab.concurrent.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Все ручки, которыми будем крутить эксперименты на практике.
 *
 * @param strategy         какая стратегия активна при старте: none|sync|trylock|rw|optimistic|queue|semaphore|deadlock
 * @param hazardMicros     сколько микросекунд «спит» каждая hazard-точка внутри критической секции
 * @param lockTimeoutMs    сколько ждём замок в trylock-стратегиях, прежде чем ответить 503 BUSY
 * @param lockShards       число поделённых замков (per-key locking) в trylock-стратегии
 * @param fair             честный ли ReentrantLock (очередь FIFO) — влияет на хвосты и throughput
 * @param optimisticRetries максимум повторов CAS-операции
 * @param queueCapacity    глубина очереди актора, сверх неё — 503 QUEUE_FULL
 * @param queueTimeoutMs   сколько ждём результата из очереди актора
 * @param permits          сколько операций одновременно пропускает semaphore-стратегия
 * @param deadlockEscape   в deadlock-стратегии использовать tryLock(timeout) вместо вечного lock()
 */
@ConfigurationProperties(prefix = "exchange")
public record LabProperties(
        String strategy,
        long hazardMicros,
        long lockTimeoutMs,
        int lockShards,
        boolean fair,
        int optimisticRetries,
        int queueCapacity,
        long queueTimeoutMs,
        int permits,
        boolean deadlockEscape) {

    public LabProperties {
        if (strategy == null || strategy.isBlank()) {
            strategy = "none";
        }
        if (hazardMicros < 0) {
            hazardMicros = 150;
        }
        if (lockTimeoutMs <= 0) {
            lockTimeoutMs = 40;
        }
        if (lockShards <= 0) {
            lockShards = 64;
        }
        if (optimisticRetries <= 0) {
            optimisticRetries = 8;
        }
        if (queueCapacity <= 0) {
            queueCapacity = 512;
        }
        if (queueTimeoutMs <= 0) {
            queueTimeoutMs = 2_000;
        }
        if (permits <= 0) {
            permits = 4;
        }
    }
}
