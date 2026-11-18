package ru.lab.concurrent.server.model;

import org.springframework.stereotype.Component;
import org.springframework.stereotype.Component;
import ru.lab.concurrent.core.dto.ScenarioRequest;
import ru.lab.concurrent.core.dto.StateView;
import ru.lab.concurrent.core.dto.LotView;
import ru.lab.concurrent.server.stats.Counters;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ЕДИНСТВЕННОЕ изменяемое состояние сервиса: лоты + кошельки + «эталонные» суммы после reset.
 *
 * <p>Класс потокобезопасен РОВНО НАСТОЛЬКО, насколько {@code ConcurrentHashMap} и
 * {@code AtomicReference} по отдельности. Каждая бизнес-операция — это несколько вызовов
 * подряд («прочитал лот → посчитал → записал лот → списал деньги → начислил продавцу»),
 * и между ними любой другой поток пролезет с тем же самым. Встроенного взаимного исключения
 * здесь нет: им занимаются стратегии из пакета {@code engine}.</p>
 *
 * <p>И отдельный урок для отчёта: {@code ConcurrentHashMap} не означает «данные не испортятся».
 * Он гарантирует атомарность ОДНОЙ своей операции, а не составной бизнес-транзакции.</p>
 */
@Component
public final class Book {

    /** id лота → текущее состояние. */
    private final Map<String, AtomicReference<LotV>> lots = new ConcurrentHashMap<>();

    /** buyerId → баланс. */
    private final Map<String, AtomicLong> buyers = new ConcurrentHashMap<>();

    /** sellerId → заработок. */
    private final Map<String, AtomicLong> sellers = new ConcurrentHashMap<>();

    /**
     * Сколько успешных записей в лот мы подтвердили клиенту. Если в конце прогона сумма
     * {@code version} по лотам меньше этого числа — часть подтверждённых записей была
     * затёрта другим потоком (lost update). Так потеря становится измеримой, а не «на глаз».
     */
    private final Map<String, AtomicLong> ackedWrites = new ConcurrentHashMap<>();

    /**
     * Сколько ЕДИНИЦ товара мы подтвердили покупателям по лоту. Сравнение с
     * {@link #initialStock} даёт два самых показательных нарушения лабы:
     * <ul>
     *   <li>{@code ackedUnits > initialStock} — OVERSELL: продали больше, чем было.
     *       Классический «два клиента на последнем товаре»: оба получили 200 OK;</li>
     *   <li>{@code stock + sold != initialStock} — LOST_UNITS: учёт расходится со складом,
     *       потому что параллельные записи посчитаны от одного устаревшего чтения.</li>
     * </ul>
     * Без этого оракула наивная стратегия выглядит почти приличной: отрицательного остатка
     * она не порождает, она порождает расхождение учёта. Важный урок: инвариант надо
     * придумывать, а не искать stacktrace.
     */
    private final Map<String, AtomicLong> ackedUnits = new ConcurrentHashMap<>();

    /** Начальный остаток лота — эталон для обоих проверок выше. */
    private final Map<String, Integer> initialStock = new ConcurrentHashMap<>();

    /** Порядок создания лотов — чтобы снимки и обходы были стабильно упорядочены. */
    private final List<String> order = new CopyOnWriteArrayList<>();

    private volatile long moneyAtStart;
    private volatile long stockAtStart;
    private volatile ScenarioRequest scenario;
    private final AtomicLong lotSeq = new AtomicLong();

    // ------------------------------ подготовка сценария -----------------------------

    /**
     * Полный сброс мира. Вызывается из {@code POST /api/lab/reset}, поэтому reset может прийти
     * в момент активных покупок — отдельная конкурентная ситуация, о которой студенты обычно
     * вспоминают, когда у них «исчезают» лоты посреди прогона.
     */
    public synchronized void reset(ScenarioRequest s) {
        lots.clear();
        buyers.clear();
        sellers.clear();
        ackedWrites.clear();
        ackedUnits.clear();
        initialStock.clear();
        order.clear();
        lotSeq.set(0);
        stockAtStart = 0;

        long money = 0;
        for (int i = 1; i <= s.buyers(); i++) {
            buyers.put("buyer-" + i, new AtomicLong(s.cashPerBuyer()));
            money += s.cashPerBuyer();
        }
        for (int i = 1; i <= s.sellers(); i++) {
            sellers.put("seller-" + i, new AtomicLong(0));
        }
        long stock = 0;
        for (int si = 1; si <= s.sellers(); si++) {
            for (int li = 1; li <= s.lotsPerSeller(); li++) {
                stock += registerLot(LotV.open("lot-" + si + "-" + li, "seller-" + si,
                        "товар-" + si + "." + li, s.pricePerLot() + li * 7L - si, s.stockPerLot()));
            }
        }
        this.moneyAtStart = money;
        this.stockAtStart = stock;
        this.scenario = s;
    }

    /**
     * Ставит лот в книжку и увеличивает «эталонное» количество товара в системе.
     * Инкремент именно здесь, чтобы созданная в ходе прогона новая витрина не выглядела
     * как нарушение «товар появился из воздуха».
     *
     * @return сколько единиц товара принес лот
     */
    public synchronized long registerLot(LotV lot) {
        lots.put(lot.id(), new AtomicReference<>(lot));
        ackedWrites.put(lot.id(), new AtomicLong(0));
        ackedUnits.put(lot.id(), new AtomicLong(0));
        initialStock.put(lot.id(), lot.stock());
        if (!order.contains(lot.id())) {
            order.add(lot.id());
        }
        stockAtStart += lot.stock();
        return lot.stock();
    }

    /** Для сценариев, где лот создаётся «заново» поверх существующего ключа. */
    public void putLotAtomic(String id, LotV lot) {
        registerLot(lot);
    }

    public void ensureSellerWallet(String sellerId) {
        sellers.computeIfAbsent(sellerId, k -> new AtomicLong(0));
    }

    public String newLotId() {
        return "lot-U" + lotSeq.incrementAndGet();
    }

    public ScenarioRequest scenario() {
        return scenario;
    }

    // ---------------------------------- доступ к лотам ------------------------------

    /**
     * Ссылка на «ячейку» лота. Возвращаем {@code AtomicReference}, а не значение, нарочно:
     * naive/lock-стратегии сделают {@code ref.set(...)} (запись поверх), а оптимистичная —
     * {@code ref.compareAndSet(старое, новое)}. Один и тот же контейнер, разная семантика.
     */
    public AtomicReference<LotV> lotRef(String id) {
        return lots.get(id);
    }

    public LotV peekLot(String id) {
        AtomicReference<LotV> ref = lots.get(id);
        return ref == null ? null : ref.get();
    }

    public List<LotV> allLots() {
        List<LotV> out = new ArrayList<>(lots.size());
        for (String id : order) {
            AtomicReference<LotV> ref = lots.get(id);
            if (ref != null && ref.get() != null) {
                out.add(ref.get());
            }
        }
        return out;
    }

    public boolean hasLot(String id) {
        return lots.containsKey(id);
    }

    // -------------------------------- доступ к кошелькам ----------------------------

    public AtomicLong buyerWallet(String buyerId) {
        return buyers.computeIfAbsent(buyerId, k -> new AtomicLong(0));
    }

    public AtomicLong sellerWallet(String sellerId) {
        return sellers.computeIfAbsent(sellerId, k -> new AtomicLong(0));
    }

    /** Подтверждение записи клиенту — нужно, чтобы сделать потерянные обновления измеримыми. */
    public void recordAckedWrite(String lotId) {
        AtomicLong c = ackedWrites.get(lotId);
        if (c != null) {
            c.incrementAndGet();
        }
    }

    /** Подтверждение продажи qty единиц — эталон для проверки oversell и расхождения учёта. */
    public void recordAckedUnits(String lotId, int qty) {
        AtomicLong c = ackedUnits.get(lotId);
        if (c != null) {
            c.addAndGet(qty);
        }
    }

    public int initialStockOf(String lotId) {
        Integer v = initialStock.get(lotId);
        return v == null ? 0 : v;
    }

    // --------------------------------------- снимки ---------------------------------

    /**
     * Снимок состояния. Сам по себе НЕ атомарен — стратегия может (и должна) закрыть его замком.
     * Здесь Broken-стратегия показывает «грязное чтение»: деньги между списанием у покупателя
     * и зачислением продавцу физически отсутствуют в системе.
     */
    public StateView snapshot(Counters counters) {
        List<LotView> views = new ArrayList<>();
        Map<String, Long> acked = new LinkedHashMap<>();
        Map<String, Long> units = new LinkedHashMap<>();
        Map<String, Integer> initial = new LinkedHashMap<>();
        for (LotV lot : allLots()) {
            views.add(lot.toView());
            acked.put(lot.id(), ackedWrites.getOrDefault(lot.id(), new AtomicLong()).get());
            units.put(lot.id(), ackedUnits.getOrDefault(lot.id(), new AtomicLong()).get());
            initial.put(lot.id(), initialStockOf(lot.id()));
        }
        Map<String, Long> buyerCopy = new TreeMap<>();
        buyers.forEach((k, v) -> buyerCopy.put(k, v.get()));
        Map<String, Long> sellerCopy = new TreeMap<>();
        sellers.forEach((k, v) -> sellerCopy.put(k, v.get()));
        return new StateView(views, buyerCopy, sellerCopy, moneyAtStart, stockTotal(), acked, units, initial,
                counters.view());
    }

    /** Текущий эталон товара в системе (созданные в ходе прогона лоты увеличивают его). */
    public synchronized long stockTotal() {
        return stockAtStart;
    }
}
