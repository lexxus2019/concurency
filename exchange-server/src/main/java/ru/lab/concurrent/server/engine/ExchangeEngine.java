package ru.lab.concurrent.server.engine;

import ru.lab.concurrent.core.dto.BuyRequest;
import ru.lab.concurrent.core.dto.Call;
import ru.lab.concurrent.core.dto.CreateLotRequest;
import ru.lab.concurrent.core.dto.LotView;
import ru.lab.concurrent.core.dto.PriceRequest;
import ru.lab.concurrent.core.dto.StateView;
import ru.lab.concurrent.server.stats.Counters;

import java.util.List;
import java.util.Optional;

/**
 * То, что студент пишет в своей «команде». Реализация = один файл, регистрация автоматическая
 * (Spring соберёт все бины в реестр), центрального {@code switch} нет — поэтому два студента,
 * добавившие по своей стратегии, не устраивают merge conflict в одном файле. Это тоже часть урока.
 *
 * <p>Правила для своей стратегии:</p>
 * <ol>
 *   <li>не трогать {@link Transactions} — логика общая, спорим только об обёртке;</li>
 *   <li>не возвращать {@code null}: либо {@link Call}, либо бросить исключение;</li>
 *   <li>конкурентный отказ обязан быть различим: {@link Call.Reason#BUSY},
 *       {@link Call.Reason#CONFLICT}, {@link Call.Reason#OVERLOADED} — и тогда клиент имеет право повторить;</li>
 *   <li>учесть, что {@link #snapshot()} вызывается параллельно с записями, и он должен быть согласованным.</li>
 * </ol>
 */
public interface ExchangeEngine {

    /** Ключ для конфига и API: {@code exchange.strategy=<name>}. */
    String name();

    /** Человекочитаемое название для таблицы результатов. */
    String title();

    /** 2−4 строки: как это работает и чем платит. Печатается в отчёте харнеса. */
    String howItWorks();

    Call createLot(CreateLotRequest request);

    Call buy(String lotId, BuyRequest request);

    Call setPrice(String lotId, PriceRequest request);

    Call cancel(String lotId);

    Optional<LotView> getLot(String id);

    List<LotView> listLots();

    /** Согласованный снимок всего состояния — по нему проверяются инварианты. */
    StateView snapshot();

    Counters counters();

    /** Длинная операция «думает» (платёжный шлюз и т.п.) — вызывается внутри hazard-точек. */
    default boolean holdsLockOverSlowWork() {
        return true;
    }
}
