package ru.lab.concurrent.server.engine.lock;

import ru.lab.concurrent.core.dto.BuyRequest;
import ru.lab.concurrent.core.dto.Call;
import ru.lab.concurrent.core.dto.LotView;
import ru.lab.concurrent.core.dto.PriceRequest;
import ru.lab.concurrent.core.dto.StateView;
import ru.lab.concurrent.server.config.LabProperties;
import ru.lab.concurrent.server.engine.AbstractEngine;
import ru.lab.concurrent.server.engine.Transactions;
import ru.lab.concurrent.server.model.Book;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Стратегия №4 — разделение на «читать можно всем, писать — по одному».
 *
 * <p>На бирже 90% трафика — витрина ({@code GET /api/lots}), и заставлять её ждать покупки
 * бессмысленно: {@link ReentrantReadWriteLock} пропускает читателей параллельно, а писателя
 * пускает одного. На таблице это видно как рост ops/s при увеличении доли чтений в нагрузке
 * (в харнесе — режим {@code --read-share=0.8}).</p>
 *
 * <p>Две тонкости, которые обычно и спрашивают на защите:</p>
 * <ol>
 *   <li><b>Согласованный снимок под readLock НЕ получается</b>: readLock не мешает писателю
 *       работать параллельно, поэтому {@link #snapshot()} здесь берёт writeLock. Иначе
 *       «правильная» стратегия всё равно отдаёт грязные данные.</li>
 *   <li>Read-locks не «разгоняют» запись: выигрыш только у чтений. Если нагрузка сплошь
 *       записывающая — получим тот же результат, что у простого замка, минус накладные расходы.</li>
 * </ol>
 */
@Component
public class ReadWriteEngine extends AbstractEngine {

    private final ReentrantReadWriteLock rw;
    private final long timeoutMillis;

    public ReadWriteEngine(Book book, LabProperties props) {
        super(book, "rw");
        this.rw = new ReentrantReadWriteLock(props.fair());
        this.timeoutMillis = props.lockTimeoutMs();
    }

    @Override
    public String name() {
        return "rw";
    }

    @Override
    public String title() {
        return "ReadWriteLock (чтения параллельно)";
    }

    @Override
    public String howItWorks() {
        return "Чтения идут одновременно, записи сериализованы. Выигрыш тем больше, чем больше"
                + " в трафике GET-ов; для сплошь записывающей нагрузки выигрыша нет.";
    }

    private Call writeLockGuarded(java.util.function.Supplier<Transactions.Outcome> body) {
        long waitStart = System.nanoTime();
        boolean got;
        try {
            got = rw.writeLock().tryLock(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            counters.busy();
            return Call.fail(503, Call.Reason.TIMEOUT, null);
        }
        counters.lockWait(microsSince(waitStart));
        if (!got) {
            counters.busy();
            return Call.fail(503, Call.Reason.BUSY, null);
        }
        long holdStart = System.nanoTime();
        try {
            Call call = publish(body.get());
            counters.hold(microsSince(holdStart));
            return call;
        } finally {
            rw.writeLock().unlock();
        }
    }

    @Override
    public Call buy(String lotId, BuyRequest request) {
        return writeLockGuarded(() -> Transactions.buy(book, lotId, request.buyerId(),
                request.qty(), request.expectedVersion()));
    }

    @Override
    public Call setPrice(String lotId, PriceRequest request) {
        return writeLockGuarded(() -> Transactions.setPrice(book, lotId, request.price(),
                request.expectedVersion()));
    }

    @Override
    public Call cancel(String lotId) {
        return writeLockGuarded(() -> Transactions.cancel(book, lotId));
    }

    @Override
    public Optional<LotView> getLot(String id) {
        rw.readLock().lock();
        try {
            counters.read();
            return Optional.ofNullable(book.peekLot(id)).map(lot -> lot.toView());
        } finally {
            rw.readLock().unlock();
        }
    }

    @Override
    public List<LotView> listLots() {
        // витрина: много одновременных читателей, каждый видит валидный, но свой по времени список
        rw.readLock().lock();
        try {
            counters.read();
            return book.allLots().stream().map(l -> l.toView()).toList();
        } finally {
            rw.readLock().unlock();
        }
    }

    @Override
    public StateView snapshot() {
        // важно: readLock не защищает от параллельных записей, поэтому снимок — под writeLock
        rw.writeLock().lock();
        try {
            counters.read();
            return book.snapshot(counters);
        } finally {
            rw.writeLock().unlock();
        }
    }
}
