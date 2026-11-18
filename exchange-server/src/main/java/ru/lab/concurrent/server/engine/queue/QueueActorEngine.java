package ru.lab.concurrent.server.engine.queue;

import jakarta.annotation.PreDestroy;
import ru.lab.concurrent.core.dto.BuyRequest;
import ru.lab.concurrent.core.dto.Call;
import ru.lab.concurrent.core.dto.PriceRequest;
import ru.lab.concurrent.core.dto.StateView;
import ru.lab.concurrent.server.config.LabProperties;
import ru.lab.concurrent.server.engine.AbstractEngine;
import ru.lab.concurrent.server.engine.Transactions;
import ru.lab.concurrent.server.model.Book;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Стратегия №6 — актор / очередь: состояние мутирует ОДИН поток, все остальные складывают
 * намерения в очередь и ждут результата.
 *
 * <p>Замков нет вообще — и при этом нет ни одной гонки: вопрос «кто изменяет лот» решается
 * архитектурно, а не синхронизацией. Так устроены single-threaded event loop (Node, Swing,
 * Kotlin {@code Dispatchers.Main}), {@code actor} в Akka, партиционированный консьюмер в Kafka
 * и {@code synchronized}-по-ключу в игровых серверах. Это ответ на вопрос студентов
 * «а можно не думать про замки вовсе» — можно, ценой латентности и пропускной способности одной очереди.</p>
 *
 * <p>Что здесь важно осознать:</p>
 * <ul>
 *   <li>throughput = 1 / (средняя длительность задачи), и он НЕ растёт от числа клиентов —
 *       ровно как у глобального замка, но очередь прозрачна: {@code queueDepth} видно;</li>
 *   <li>переполнение очереди — это не исключение, а контракт: 503 QUEUE_FULL + backpressure,</li>
 *   <li>согласованный снимок бесплатный: {@link #snapshot()} — просто ещё одна задача в очереди;</li>
 *   <li>модель «клиент ждёт результат» не масштабируется: в проде ответ уходит асинхронно
 *       (callback/second topic), и тогда на очереди можно пропускать в разы больше.</li>
 * </ul>
 */
@Component
public class QueueActorEngine extends AbstractEngine {

    private final ThreadPoolExecutor actor;
    private final long waitMillis;

    public QueueActorEngine(Book book, LabProperties props) {
        super(book, "queue");
        this.waitMillis = props.queueTimeoutMs();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "exchange-actor");
            t.setDaemon(true);
            return t;
        };
        // ровно один рабочий поток + ограниченная очередь = «одна очередь на всю биржу»
        this.actor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(16, props.queueCapacity())), factory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public String name() {
        return "queue";
    }

    @Override
    public String title() {
        return "очередь + один поток (актор)";
    }

    @Override
    public String howItWorks() {
        return "Мутации сериализуются одним потоком: замки не нужны вовсе. Плата — латентность"
                + " ожидания очереди; переполнение отдаём как 503 QUEUE_FULL (backpressure).";
    }

    private Call submit(Callable<Call> task) {
        counters.queueDepth(actor.getQueue().size() + (actor.getActiveCount() > 0 ? 1 : 0));
        long start = System.nanoTime();
        Future<Call> future;
        try {
            future = actor.submit(task);
        } catch (RejectedExecutionException e) {
            // очередь забита: правильнее отказать и дать клиенту повторить, чем копить долгий хвост
            counters.busy();
            return Call.fail(503, Call.Reason.QUEUE_FULL, null);
        }
        try {
            Call call = future.get(waitMillis, TimeUnit.MILLISECONDS);
            counters.hold(microsSince(start));
            return call;
        } catch (TimeoutException e) {
            future.cancel(false);
            counters.busy();
            return Call.fail(503, Call.Reason.TIMEOUT, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(false);
            counters.busy();
            return Call.fail(503, Call.Reason.TIMEOUT, null);
        } catch (ExecutionException e) {
            counters.rejected();
            return Call.fail(500, Call.Reason.OVERLOADED, null);
        }
    }

    @Override
    public Call buy(String lotId, BuyRequest request) {
        return submit(() -> publish(Transactions.buy(book, lotId, request.buyerId(),
                request.qty(), request.expectedVersion())));
    }

    @Override
    public Call setPrice(String lotId, PriceRequest request) {
        return submit(() -> publish(Transactions.setPrice(book, lotId, request.price(),
                request.expectedVersion())));
    }

    @Override
    public Call cancel(String lotId) {
        return submit(() -> publish(Transactions.cancel(book, lotId)));
    }

    @Override
    public StateView snapshot() {
        // снимок — такая же задача в той же очереди, поэтому он атомарен относительно всех записей:
        // ни одна покупка не «протечёт» между нашими чтениями. Согласованность без единого замка.
        final StateView[] box = new StateView[1];
        submit(() -> {
            counters.read();
            box[0] = book.snapshot(counters);
            return Call.ok(null);
        });
        return box[0] != null ? box[0] : book.snapshot(counters);
    }

    @Override
    public Call createLot(ru.lab.concurrent.core.dto.CreateLotRequest request) {
        return submit(() -> super.createLot(request));
    }

    @PreDestroy
    public void shutdown() {
        actor.shutdownNow();
    }
}
