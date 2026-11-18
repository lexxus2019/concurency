package ru.lab.concurrent.server.engine.limiter;

import ru.lab.concurrent.core.dto.BuyRequest;
import ru.lab.concurrent.core.dto.Call;
import ru.lab.concurrent.core.dto.PriceRequest;
import ru.lab.concurrent.server.config.LabProperties;
import ru.lab.concurrent.server.engine.AbstractEngine;
import ru.lab.concurrent.server.engine.Transactions;
import ru.lab.concurrent.server.model.Book;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Стратегия №7 — семафор, то есть ограничение числа одновременных обращений к ресурсу.
 *
 * <p><b>Это задание с подвохом, и оно сделано подвохом намеренно.</b> Семафор защищает
 * ресурс от перегрузки (пул соединений, лимит внешнего платёжного API, «не больше N
 * одновременных партий»), но он НЕ делает составную операцию атомарной: пока внутри
 * разрешённых permits работают хотя бы два потока, {@link Transactions#buy} всё так же
 * читает и пишет поверх. Нарушения инвариантов просто становятся РЕДЖЕ — что, кстати,
 * самый опасный вид бага в проде: тесты зелёные три месяца подряд.</p>
 *
 * <p>Проверьте это руками: {@code exchange.permits=2} даёт заметные нарушения,
 * {@code permits=1} превращает семафор в примитивный замок — корректно, но тогда
 * {@code Semaphore} это просто дорогая и менее удобная замена {@code ReentrantLock}:
 * у него нет ни таймаута ожидания в API lock'а, ни перевызываемости, ни прав на владельца.
 * Отличный ответ на экзаменационный вопрос «чём семафор отличается от замка».</p>
 */
@Component
public class SemaphoreAdmissionEngine extends AbstractEngine {

    private final Semaphore permits;
    private final int permitCount;
    private final long acquireTimeoutMillis;

    public SemaphoreAdmissionEngine(Book book, LabProperties props) {
        super(book, "semaphore");
        this.permitCount = props.permits();
        this.permits = new Semaphore(permitCount, true);
        this.acquireTimeoutMillis = props.lockTimeoutMs();
    }

    @Override
    public String name() {
        return "semaphore";
    }

    @Override
    public String title() {
        return "семафор (permits=" + permitCount + ")";
    }

    @Override
    public String howItWorks() {
        return "Ограничивает одновременность, но НЕ даёт атомарности: корректность появляется"
                + " только при permits=1, и тогда это просто медленный замок.";
    }

    private Call admit(java.util.function.Supplier<Transactions.Outcome> body) {
        long waitStart = System.nanoTime();
        boolean got;
        try {
            got = permits.tryAcquire(acquireTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            counters.busy();
            return Call.fail(503, Call.Reason.TIMEOUT, null);
        }
        counters.lockWait(microsSince(waitStart));
        if (!got) {
            // сюда попадаем, когда очередь желающих длиннее, чем ёмкость ресурса:
            // честно отказываем (503 + Retry-After), а не держим поток до бесконечности
            counters.busy();
            return Call.fail(503, Call.Reason.OVERLOADED, null);
        }
        long holdStart = System.nanoTime();
        try {
            Call call = publish(body.get());
            counters.hold(microsSince(holdStart));
            return call;
        } finally {
            permits.release();   // обязательно в finally — иначе «утечка» permits убьёт сервис за час
        }
    }

    @Override
    public Call buy(String lotId, BuyRequest request) {
        return admit(() -> Transactions.buy(book, lotId, request.buyerId(),
                request.qty(), request.expectedVersion()));
    }

    @Override
    public Call setPrice(String lotId, PriceRequest request) {
        return admit(() -> Transactions.setPrice(book, lotId, request.price(), request.expectedVersion()));
    }

    @Override
    public Call cancel(String lotId) {
        return admit(() -> Transactions.cancel(book, lotId));
    }
}
