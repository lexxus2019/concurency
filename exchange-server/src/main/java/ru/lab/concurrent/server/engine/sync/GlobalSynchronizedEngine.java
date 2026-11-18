package ru.lab.concurrent.server.engine.sync;

import ru.lab.concurrent.core.dto.BuyRequest;
import ru.lab.concurrent.core.dto.Call;
import ru.lab.concurrent.core.dto.PriceRequest;
import ru.lab.concurrent.core.dto.StateView;
import ru.lab.concurrent.server.engine.AbstractEngine;
import ru.lab.concurrent.server.engine.Transactions;
import ru.lab.concurrent.server.model.Book;
import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Стратегия №1 — «просто поставь synchronized везде». Первый рефлекс, и он действительно
 * даёт корректность: инварианты перестают нарушаться целиком.
 *
 * <p>Плата: один замок на весь сервис. Пока один поток считает покупку, ждут все, включая
 * читателя, который просто хотел посмотреть цену. На таблице видно, что throughput
 * превращается в «1 / длительность критической секции» и перестаёт зависеть от числа
 * клиентов. Тут разговор и уходит от «правильно/неправильно» к «какую цену мы платим
 * за правильность».</p>
 *
 * <p>Технические замечания для отчёта:
 * <ul>
 *   <li>использован {@link ReentrantLock}(fair=true), а не {@code synchronized}, — семантика та же,
 *       но замок видно как объект: им можно управлять, ставить таймаут, читать статистику;</li>
 *   <li>{@link ru.lab.concurrent.server.model.Hazard}-точки остались ВНУТРИ секции — это ровно тот
 *       антипаттерн «держать замок поверх медленной работы», и именно он даёт провал по скорости;</li>
 *   <li>чтения тоже под замком, иначе {@link #snapshot()} останется несогласованным.</li>
 * </ul></p>
 */
@Component
public class GlobalSynchronizedEngine extends AbstractEngine {

    private final ReentrantLock global = new ReentrantLock(true);

    public GlobalSynchronizedEngine(Book book) {
        super(book, "sync");
    }

    @Override
    public String name() {
        return "sync";
    }

    @Override
    public String title() {
        return "synchronized на весь сервис";
    }

    @Override
    public String howItWorks() {
        return "Один замок сериализует всё, включая чтения. Корректно на 100%,"
                + " зато очередь на ресурс одна на всех, а latency растёт линейно с числом клиентов.";
    }

    @Override
    public Call buy(String lotId, BuyRequest request) {
        return guardedTx(global, 0, () -> Transactions.buy(book, lotId, request.buyerId(),
                request.qty(), request.expectedVersion()));
    }

    @Override
    public Call setPrice(String lotId, PriceRequest request) {
        return guardedTx(global, 0, () -> Transactions.setPrice(book, lotId, request.price(),
                request.expectedVersion()));
    }

    @Override
    public Call cancel(String lotId) {
        return guardedTx(global, 0, () -> Transactions.cancel(book, lotId));
    }

    /**
     * Витрина — это СОГЛАСОВАННЫЙ список многих лотов, а не одно поле, поэтому её тоже
     * приходится закрывать замком. Именно здесь {@code sync} и проигрывает {@code rw}:
     * покупателю, который просто смотрит цены, в этой стратегии нужно ждать свой черёд
     * в общей очереди записей. Смотри колонку p95чтений в таблице харнеса.
     */
    @Override
    public java.util.List<ru.lab.concurrent.core.dto.LotView> listLots() {
        global.lock();
        try {
            counters.read();
            return book.allLots().stream().map(lot -> lot.toView()).toList();
        } finally {
            global.unlock();
        }
    }

    @Override
    public StateView snapshot() {
        // согласованный снимок: пока читаем всё подряд, никто не пишет
        global.lock();
        try {
            counters.read();
            return book.snapshot(counters);
        } finally {
            global.unlock();
        }
    }
}
