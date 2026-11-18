package ru.lab.concurrent.server.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import ru.lab.concurrent.core.check.Invariants;
import ru.lab.concurrent.core.check.Violation;
import ru.lab.concurrent.core.dto.BuyRequest;
import ru.lab.concurrent.core.dto.Call;
import ru.lab.concurrent.core.dto.PriceRequest;
import ru.lab.concurrent.core.dto.StateView;
import ru.lab.concurrent.server.config.LabProperties;
import ru.lab.concurrent.server.model.Book;
import ru.lab.concurrent.server.model.Hazard;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Машинка для проверки студенческой стратегии: одна и та же нагрузка, один и тот же сценарий,
 * разные обёртки. Запускать {@code mvn -pl exchange-server test} — это «definition of done»
 * для любого задания лабы: стратегия считается готовой, когда эти тесты зелёные.
 *
 * <p>Обрати внимание, что тест проверяет НЕ «не было исключений», а инварианты данных.
 * В Broken-стратегии исключений нет вообще — она просто тихо портит склад и кассу.</p>
 */
class EngineInvariantTest {

    private static final int BUYER_THREADS = 8;
    private static final int SELLER_THREADS = 2;
    private static final int OPS_PER_BUYER = 120;
    private static final int OPS_PER_SELLER = 40;
    private static final int BUYER_IDS = 4;

    private final Book book = new Book();

    @AfterEach
    void resetHazard() {
        Hazard.setMicros(150);
    }

    static Stream<String> fullyCleanStrategies() {
        return Stream.of("sync", "locks", "rw", "optimistic", "queue");
    }

    private ExchangeEngine engine(String name, LabProperties props) {
        return TestEngines.all(book, props).stream()
                .filter(e -> e.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("нет стратегии " + name));
    }

    /** Прогон нагрузки; возвращает снимок состояния после остановки всех потоков. */
    private StateView drive(ExchangeEngine engine) throws Exception {
        book.reset(TestEngines.scenario(BUYER_IDS, 8, 200));
        List<String> lotIds = new ArrayList<>(book.allLots().stream().map(l -> l.id()).toList());
        ExecutorService pool = Executors.newFixedThreadPool(BUYER_THREADS + SELLER_THREADS);
        try {
            List<Callable<Long>> tasks = new ArrayList<>();
            for (int t = 0; t < BUYER_THREADS; t++) {
                // принципиально: id покупателя совпадают у разных потоков — один покупатель
                // «работает руками двух потоков». Это и вскрывает слабое место ключа-по-лоту
                final String buyerId = "buyer-" + (1 + (t % BUYER_IDS));
                final int seed = t;
                tasks.add(() -> {
                    Random random = new Random(seed);
                    long applied = 0;
                    for (int i = 0; i < OPS_PER_BUYER; i++) {
                        String lotId = lotIds.get(random.nextInt(lotIds.size()));
                        Call call = engine.buy(lotId, BuyRequest.blind(buyerId, 1 + random.nextInt(3)));
                        if (call.success()) {
                            applied++;
                        }
                    }
                    return applied;
                });
            }
            for (int t = 0; t < SELLER_THREADS; t++) {
                final int seed = 100 + t;
                tasks.add(() -> {
                    Random random = new Random(seed);
                    long applied = 0;
                    for (int i = 0; i < OPS_PER_SELLER; i++) {
                        String lotId = lotIds.get(random.nextInt(lotIds.size()));
                        // продавец делает уценку «от того, что увидел» — классический lost update
                        var lot = engine.getLot(lotId).orElse(null);
                        if (lot == null) {
                            continue;
                        }
                        long next = Math.max(1, lot.price() - 1);
                        Call call = engine.setPrice(lotId, PriceRequest.blind(next));
                        if (call.success()) {
                            applied++;
                        }
                    }
                    return applied;
                });
            }
            List<Future<Long>> futures = pool.invokeAll(tasks);
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "нагрузка не завершилась за минуту");
            long sum = 0;
            for (Future<Long> f : futures) {
                sum += f.get();
            }
            assertTrue(sum > 0, "ни одна операция не прошла — тест бессмысленный");
            return engine.snapshot();
        } finally {
            pool.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0} не должна портить данные")
    @MethodSource("fullyCleanStrategies")
    @DisplayName("корректные стратегии: ни одного нарушения инвариантов")
    void correctEnginesKeepInvariants(String name) throws Exception {
        LabProperties props = TestEngines.props(20, 1);
        Hazard.setMicros(20);
        StateView state = drive(engine(name, props));
        List<Violation> violations = Invariants.check(state);
        assertTrue(violations.isEmpty(),
                () -> name + " сломала инварианты: " + violations + "\n" + dump(state));
    }

    @Test
    @DisplayName("наивная реализация ОБЯЗАНА воспроизводить гонку — иначе тест бесполезен")
    void brokenEngineReproducesRace() throws Exception {
        Hazard.setMicros(80);
        StateView state = drive(engine("none", TestEngines.props(80, 1)));
        List<Violation> violations = Invariants.check(state);
        assertFalse(violations.isEmpty(),
                () -> "гонка не воспроизвелась: увеличь hazard-micros или число потоков\n" + dump(state));
    }

    @Test
    @DisplayName("семафор сам по себе корректности не даёт (задание с подвохом)")
    void semaphoreAloneIsNotAtomicity() throws Exception {
        Hazard.setMicros(80);
        StateView state = drive(engine("semaphore", TestEngines.props(80, 4)));
        List<Violation> violations = Invariants.check(state);
        assertFalse(violations.isEmpty(),
                () -> "ожидали нарушений при permits=4 — их нет, нагрузка слишком слабая\n" + dump(state));
    }

    @Test
    @DisplayName("замок по ключу «лот» защищает лот, но не кошелёк")
    void lotKeyedLockProtectsLotOnly() throws Exception {
        Hazard.setMicros(60);
        StateView state = drive(engine("trylock", TestEngines.props(60, 1)));
        List<Violation> violations = Invariants.check(state);
        assertTrue(violations.stream().noneMatch(v -> v.code().equals(Invariants.OVERSELL)),
                () -> "по лотам должно быть чисто: " + violations);
        assertTrue(violations.stream().noneMatch(v -> v.code().equals(Invariants.LOST_UPDATE)),
                () -> "по версиям должно быть чисто: " + violations);
        // а вот деньги могут быть испорчены — несколько потоков одного покупателя в разных шардах
        System.out.println("[trylock] остаток нарушений (ожидаем про кошельки): " + Invariants.summarize(violations));
    }

    @Test
    @DisplayName("сумма денег и товара сходится при корректной стратегии")
    void conservationHolds() throws Exception {
        Hazard.setMicros(20);
        StateView state = drive(engine("locks", TestEngines.props(20, 1)));
        assertEquals(state.moneyTotalAtStart(), state.moneyNow(), "деньги должны сохраниться");
        assertEquals(state.stockTotalAtStart(), state.stockNow(), "товар должен сохраниться");
        assertTrue(state.stats().opsOk() > 0);
    }

    private static String dump(StateView state) {
        return "acked=" + state.ackedTotal() + " versions=" + state.versionTotal()
                + " money=" + state.moneyNow() + "/" + state.moneyTotalAtStart()
                + " stock=" + state.stockNow() + "/" + state.stockTotalAtStart()
                + " ops=" + state.stats();
    }
}
