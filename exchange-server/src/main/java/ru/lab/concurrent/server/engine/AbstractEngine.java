package ru.lab.concurrent.server.engine;

import ru.lab.concurrent.core.dto.Call;
import ru.lab.concurrent.core.dto.CreateLotRequest;
import ru.lab.concurrent.core.dto.LotView;
import ru.lab.concurrent.core.dto.StateView;
import ru.lab.concurrent.server.model.Book;
import ru.lab.concurrent.server.model.LotV;
import ru.lab.concurrent.server.stats.Counters;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.function.Supplier;

/** Общая «не синхронизированная» часть всех стратегий: создание лота, чтения, снимок, замеры. */
public abstract class AbstractEngine implements ExchangeEngine {

    protected final Book book;
    protected final Counters counters;

    protected AbstractEngine(Book book, String name) {
        this.book = book;
        this.counters = new Counters(name);
    }

    @Override
    public Counters counters() {
        return counters;
    }

    /** Новый лот — новый ключ, конкуренции почти нет; поэтому здесь обёртка не нужна. */
    @Override
    public Call createLot(CreateLotRequest request) {
        if (request.price() <= 0 || request.stock() < 0) {
            counters.rejected();
            return Call.fail(400, Call.Reason.OUT_OF_STOCK, null);
        }
        String id = book.newLotId();
        book.ensureSellerWallet(request.sellerId());
        LotV lot = LotV.open(id, request.sellerId(), request.title(), request.price(), request.stock());
        book.registerLot(lot);
        counters.ok();
        return Call.ok(lot.toView());
    }

    @Override
    public Optional<LotView> getLot(String id) {
        counters.read();
        return Optional.ofNullable(book.peekLot(id)).map(LotV::toView);
    }

    @Override
    public List<LotView> listLots() {
        counters.read();
        return book.allLots().stream().map(LotV::toView).toList();
    }

    /** По умолчанию снимок собирается «как попало» — см. стратегии, где он под замком. */
    @Override
    public StateView snapshot() {
        counters.read();
        return book.snapshot(counters);
    }

    // -------------------------------------- вспомогательное --------------------------

    /**
     * Выполнение {@code body} под замком: засчитывает ожидание замка и длительность удержания.
     * Это единственный «измерительный прибор» лабы — по {@code lockWait} видно очередь
     * на ресурс, по {@code maxHold} — что критическая секция слишком жирная.
     *
     * @param timeoutMillis {@code <= 0} означает «ждать сколько угодно», то есть семантику
     *        обычного {@code synchronized}: отказы тогда не возникают вообще, а очередь
     *        выражается только в латентности. {@code > 0} — {@code tryLock} и честный 503 BUSY.
     */
    protected Call guarded(Lock lock, long timeoutMillis, Supplier<Call> body, Supplier<Call> onBusy) {
        long waitStart = System.nanoTime();
        boolean locked = false;
        try {
            if (timeoutMillis <= 0) {
                lock.lock();               // ждём всегда: это и есть «просто synchronized»
                locked = true;
            } else {
                locked = lock.tryLock(timeoutMillis, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            counters.busy();
            return Call.fail(503, Call.Reason.TIMEOUT, null);
        }
        counters.lockWait(microsSince(waitStart));
        if (!locked) {
            counters.busy();
            return onBusy.get();
        }
        long holdStart = System.nanoTime();
        try {
            Call call = body.get();
            counters.hold(microsSince(holdStart));
            return call;
        } finally {
            lock.unlock();
        }
    }

    protected static long microsSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000;
    }

    /** Транслирует «мы применили»/«мы отказали» в ответ клиенту + счётчик. */
    protected Call publish(Transactions.Outcome outcome) {
        if (outcome.applied()) {
            counters.ok();
        } else {
            counters.rejected();
        }
        return outcome.call();
    }

    protected Call busyCall() {
        return Call.fail(503, Call.Reason.BUSY, null);
    }

    /**
     * То же, что {@link #guarded}, но с транзакцией внутри: взятие замка → применение
     * {@link Transactions} → подсчёт результата → освобождение. Все lock-стратегии лабы
     * отличаются только тем, какой замок сюда передаётся и с каким таймаутом.
     */
    protected Call guardedTx(Lock lock, long timeoutMillis, Supplier<Transactions.Outcome> body) {
        return guarded(lock, timeoutMillis, () -> publish(body.get()), AbstractEngine::busyCallStatic);
    }

    private static Call busyCallStatic() {
        return Call.fail(503, Call.Reason.BUSY, null);
    }

    protected Call overloadedCall(String reason) {
        return Call.fail(503, reason, null);
    }
}
