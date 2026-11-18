package ru.lab.concurrent.server.engine.deadlock;

import ru.lab.concurrent.core.dto.BuyRequest;
import ru.lab.concurrent.core.dto.Call;
import ru.lab.concurrent.core.dto.PriceRequest;
import ru.lab.concurrent.core.dto.StateView;
import ru.lab.concurrent.server.config.LabProperties;
import ru.lab.concurrent.server.engine.AbstractEngine;
import ru.lab.concurrent.server.engine.Transactions;
import ru.lab.concurrent.server.model.Book;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Стратегия №8 — специально написана неправильно: взаимоблокировка на двух ресурсах.
 *
 * <p>Два замка: лот и кошелёк. Покупка берёт их в порядке «лот → кошелёк продавца»,
 * смена цены — в порядке «кошелёк продавца → лот». Каждый держит свой и ждёт чужой:
 * круг замкнулся, ни один не освободится никогда. Логи при этом молчат — в этом и ужас:
 * deadlock не бросает исключение, он просто перестаёт обслуживать запросы, а через
 * несколько минут кладёт весь сервис, потому что поток Tomcat из пула не возвращается.</p>
 *
 * <p>Три способа выбраться, и все три стоят в конфиге:</p>
 * <ol>
 *   <li>{@code exchange.deadlock-escape=true} → {@code tryLock(timeout)}: один поток проигрывает,
 *       отвечает 503 BUSY, остальные живут (стратегия {@code trylock} в лабе);</li>
 *   <li>единый порядок захвата замков (см. {@code TwoLevelLockEngine#orderedLocks}) —
 *       дорого, но исключает цикл конструктивно;</li>
 *   <li>не держать два замка одновременно вовсе: один атомарный шаг по одному объекту
 *       (очередь/актор или CAS).</li>
 * </ol>
 *
 * <p>Как увидеть напрямую: {@code GET /api/lab/threads} — он вызывает
 * {@code ThreadMXBean.findDeadlockedThreads()} и печатает имена и стеки виновников.</p>
 */
@Component
public class DeadlockEngine extends AbstractEngine {

    private final ReentrantLock lotLock = new ReentrantLock();
    private final ReentrantLock walletLock = new ReentrantLock();
    private final boolean escape;
    private final long timeoutMillis;

    public DeadlockEngine(Book book, LabProperties props) {
        super(book, "deadlock");
        this.escape = props.deadlockEscape();
        this.timeoutMillis = props.lockTimeoutMs();
    }

    @Override
    public String name() {
        return "deadlock";
    }

    @Override
    public String title() {
        return "взаимоблокировка (лот ↔ кошелёк)";
    }

    @Override
    public String howItWorks() {
        return "Два замка, разные порядок захвата в двух операциях → цикл. Никаких исключений,"
                + " просто зависшие потоки и 503 по таймауту клиента. escape=" + escape;
    }

    /** Покупка: лот, потом кошелёк. */
    @Override
    public Call buy(String lotId, BuyRequest request) {
        if (!acquire(lotLock)) {
            return busy();
        }
        try {
            if (!acquire(walletLock)) {
                return busy();
            }
            try {
                return publish(Transactions.buy(book, lotId, request.buyerId(),
                        request.qty(), request.expectedVersion()));
            } finally {
                walletLock.unlock();
            }
        } finally {
            lotLock.unlock();
        }
    }

    /** Продажа/цена: кошелёк, потом лот. Порядок противоположен покупке — вот и весь deadlock. */
    @Override
    public Call setPrice(String lotId, PriceRequest request) {
        if (!acquire(walletLock)) {
            return busy();
        }
        try {
            if (!acquire(lotLock)) {
                return busy();
            }
            try {
                return publish(Transactions.setPrice(book, lotId, request.price(), request.expectedVersion()));
            } finally {
                lotLock.unlock();
            }
        } finally {
            walletLock.unlock();
        }
    }

    /** Отзыв лота идёт тем же «неправильным» порядком, что и смена цены. */
    @Override
    public Call cancel(String lotId) {
        if (!acquire(walletLock)) {
            return busy();
        }
        try {
            if (!acquire(lotLock)) {
                return busy();
            }
            try {
                return publish(Transactions.cancel(book, lotId));
            } finally {
                lotLock.unlock();
            }
        } finally {
            walletLock.unlock();
        }
    }

    @Override
    public StateView snapshot() {
        // снимок сознательно без замков: он и в корректных стратегиях тут не при чём
        counters.read();
        return book.snapshot(counters);
    }

    /**
     * Возвращает true, если замок взят. При {@code escape=false} это вечный {@code lock()} —
     * то есть поток уйдёт в deadlock и будет ждать буквально всегда.
     */
    private boolean acquire(ReentrantLock lock) {
        long waitStart = System.nanoTime();
        if (!escape) {
            lock.lock();
            counters.lockWait(microsSince(waitStart));
            return true;
        }
        try {
            boolean got = lock.tryLock(timeoutMillis, TimeUnit.MILLISECONDS);
            counters.lockWait(microsSince(waitStart));
            return got;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private Call busy() {
        counters.busy();
        return Call.fail(503, Call.Reason.BUSY, null);
    }
}
