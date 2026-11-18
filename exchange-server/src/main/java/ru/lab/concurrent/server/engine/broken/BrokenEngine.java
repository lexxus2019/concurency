package ru.lab.concurrent.server.engine.broken;

import ru.lab.concurrent.core.dto.BuyRequest;
import ru.lab.concurrent.core.dto.Call;
import ru.lab.concurrent.core.dto.PriceRequest;
import ru.lab.concurrent.core.dto.StateView;
import ru.lab.concurrent.server.engine.AbstractEngine;
import ru.lab.concurrent.server.engine.Transactions;
import ru.lab.concurrent.server.model.Book;
import org.springframework.stereotype.Component;

/**
 * Стратегия №0 — «как написал бы человек, который не думал о конкурентности».
 *
 * <p>Ничего не сломано синтаксически, исключений не будет никогда, логи чистые.
 * Сервис просто иногда продаёт больше товара, чем есть, теряет деньги «в моменте»
 * и затирает подтверждённые клиенту записи. Это стартовый код лабы: его нужно
 * не переписывать, а обёртывать.</p>
 *
 * <p>Смотри на {@link Transactions#buy} как на расписание поезда: вызовы идут подряд,
 * между ними другой поток делает ровно те же вызовы.</p>
 */
@Component
public class BrokenEngine extends AbstractEngine {

    public BrokenEngine(Book book) {
        super(book, "none");
    }

    @Override
    public String name() {
        return "none";
    }

    @Override
    public String title() {
        return "Broken (без синхронизации)";
    }

    @Override
    public String howItWorks() {
        return "Никакой защиты. Максимальный throughput, потому что никто никого не ждёт,"
                + " и ровно те же цифры при этом означают испорченные данные.";
    }

    @Override
    public Call buy(String lotId, BuyRequest request) {
        // РИСК: два потока проходят проверку stock() одновременно и оба «пишут» поверх
        return publish(Transactions.buy(book, lotId, request.buyerId(), request.qty(), request.expectedVersion()));
    }

    @Override
    public Call setPrice(String lotId, PriceRequest request) {
        return publish(Transactions.setPrice(book, lotId, request.price(), request.expectedVersion()));
    }

    @Override
    public Call cancel(String lotId) {
        return publish(Transactions.cancel(book, lotId));
    }

    @Override
    public StateView snapshot() {
        // РИСК: снимок собирается по кускам, пока записи летят — отсюда «пропавшие» деньги
        counters.read();
        return book.snapshot(counters);
    }
}
