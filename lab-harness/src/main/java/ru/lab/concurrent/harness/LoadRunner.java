package ru.lab.concurrent.harness;

import ru.lab.concurrent.core.check.Invariants;
import ru.lab.concurrent.core.dto.BuyRequest;
import ru.lab.concurrent.core.dto.Call;
import ru.lab.concurrent.core.dto.CreateLotRequest;
import ru.lab.concurrent.core.dto.LotView;
import ru.lab.concurrent.core.dto.PriceRequest;
import ru.lab.concurrent.core.dto.ScenarioRequest;
import ru.lab.concurrent.core.dto.StateView;
import ru.lab.concurrent.core.http.ExchangeClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Генератор нагрузки. Код один и тот же для всех стратегий — иначе сравнивать нечего.
 * Смешивает операции покупателей (частые, короткие) и продавца (редкие, жадные до ресурса):
 * именно эта асимметрия и рождает конфликты в реальной команде.
 */
final class LoadRunner {

    record Config(ScenarioRequest scenario, int buyerThreads, int opsPerBuyer, int sellerThreads,
                  int opsPerSeller, int qtyMin, int qtyMax, int buyerIds, double readShare,
                  boolean useIfMatch, long verifyDelayMillis, String pickMode) {

        /**
         * Как покупатель выбирает лот. Это не косметика: профиль выбора определяет, СХОДЯТСЯ ли
         * операции на одном ресурсе.
         * <ul>
         *   <li>{@code min} — все идут в самый дешёвый лот: война за один объект, оптимистичная
         *       блокировка задыхается, зато замок «по лоту» случайно спасает и кошелёк;</li>
         *   <li>{@code random} — клиенты растекуются по витрине: видно настоящую выгоду декомпозиции
         *       замка, и одновременно вскрывается дыра «замок по лоту не защищает кошелёк»;</li>
         *   <li>{@code mixed} — как в жизни (50/50).</li>
         * </ul>
         */
        Config {
            if (pickMode == null || pickMode.isBlank()) {
                pickMode = "mixed";
            }
        }
    }

    private LoadRunner() {
    }

    /** Результаты одного прогона стратегии. */
    private record Tally(LongAdder ok, LongAdder busy, LongAdder conflict, LongAdder rejected, LongAdder reads,
                         LongAdder priceWrites, AtomicLong priceLost,
                         List<Long> readMicros, List<Long> writeMicros) {

        Tally() {
            this(new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder(),
                    new LongAdder(), new AtomicLong(),
                    Collections.synchronizedList(new ArrayList<>()),
                    Collections.synchronizedList(new ArrayList<>()));
        }
    }

    static RunResult run(ExchangeClient client, String strategy, Config cfg) throws InterruptedException {
        client.strategy(strategy);
        client.reset(cfg.scenario());
        // прогрев: JIT и пул Tomcat, иначе первая стратегия всегда выглядит виноватой
        warmUp(client);

        Tally tally = new Tally();
        Map<String, Long> lastAckedPrice = new ConcurrentHashMap<>();
        List<String> lots = client.lots().stream().map(LotView::id).toList();
        if (lots.isEmpty()) {
            throw new IllegalStateException("после reset нет ни одного лота — сценарий пустой");
        }

        ExecutorService pool = Executors.newFixedThreadPool(cfg.buyerThreads() + cfg.sellerThreads());
        long startedAt = System.nanoTime();

        for (int t = 0; t < cfg.buyerThreads(); t++) {
            // принципиально: id покупателей повторяются у разных потоков — один покупатель
            // «работает руками двух потоков». Так вскрывается, что замок по ключу «лот»
            // не защищает кошелёк
            final String buyerId = "buyer-" + (1 + (t % Math.max(1, cfg.buyerIds())));
            final int seed = t;
            pool.submit(() -> driveBuyer(client, buyerId, seed, cfg, tally));
        }
        for (int t = 0; t < cfg.sellerThreads(); t++) {
            final int threadIndex = t;
            // продавец владеет своими лотами: у каждого лота ровно один писатель цены,
            // иначе наш собственный продавец станет источником потерь и мы обвиним сервер в чужом баге
            final List<String> owned = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                Call c = client.createLot(new CreateLotRequest("seller-U" + (threadIndex + 1),
                        "витрина-U" + (threadIndex + 1) + "-" + (i + 1), 150, 400));
                if (c.success() && c.lot() != null) {
                    owned.add(c.lot().id());
                }
            }
            pool.submit(() -> driveSeller(client, owned, threadIndex, cfg, tally, lastAckedPrice));
        }

        pool.shutdown();
        if (!pool.awaitTermination(10, TimeUnit.MINUTES)) {
            System.out.println("  ! прогон не завершился за 10 минут — сервис стоит (см. GET /api/lab/threads)");
            pool.shutdownNow();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }
        double seconds = (System.nanoTime() - startedAt) / 1e9;

        StateView state = client.state();
        return new RunResult(
                strategy,
                titleOf(client, strategy),
                Math.round(seconds * 100) / 100.0,
                tally.ok().sum(),
                tally.busy().sum(),
                tally.conflict().sum(),
                tally.rejected().sum(),
                tally.reads().sum(),
                Math.round(tally.ok().sum() / seconds),
                percentile(new ArrayList<>(tally.readMicros()), 0.95) / 1000.0,
                percentile(new ArrayList<>(tally.writeMicros()), 0.95) / 1000.0,
                state.stats().maxHoldMicros(),
                state.stats().retries(),
                tally.priceWrites().sum(),
                tally.priceLost().get(),
                Invariants.summarize(Invariants.check(state)));
    }

    private static void warmUp(ExchangeClient client) {
        for (int i = 0; i < 20; i++) {
            client.lots();
        }
        client.lots().stream().findFirst().ifPresent(l -> client.buy(l.id(), BuyRequest.blind("buyer-1", 1)));
    }

    private static void driveBuyer(ExchangeClient client, String buyerId, int seed, Config cfg, Tally tally) {
        Random random = new Random(seed * 37 + 11);
        for (int i = 0; i < cfg.opsPerBuyer(); i++) {
            long start = System.nanoTime();
            List<LotView> lots = client.lots();
            if (random.nextDouble() < cfg.readShare()) {
                tally.reads().increment();
                tally.readMicros().add(micros(start));
                continue;
            }
            LotView pick = pickLot(lots, random, cfg);
            if (pick == null) {
                tally.rejected().increment();
                tally.readMicros().add(micros(start));
                continue;
            }
            int qty = cfg.qtyMin() + (cfg.qtyMax() > cfg.qtyMin() ? random.nextInt(cfg.qtyMax() - cfg.qtyMin() + 1) : 0);
            Call call = cfg.useIfMatch()
                    ? conditionalBuyWithRetry(client, buyerId, pick.id(), qty)
                    : client.buy(pick.id(), BuyRequest.blind(buyerId, qty));
            tally.writeMicros().add(micros(start));
            classify(call, tally);
        }
    }

    /**
     * Покупка с версией и повтором: перечитать лот → попробовать → при 409 перечитать и повторить.
     * Это обязательный «ответный код» клиента на оптимистичную блокировку сервера: сервер не ждёт,
     * но работу по повтору кто-то должен делать, и это клиент.
     */
    private static Call conditionalBuyWithRetry(ExchangeClient client, String buyerId, String lotId, int qty) {
        Call last = null;
        for (int attempt = 0; attempt < 8; attempt++) {
            LotView fresh = client.lot(lotId).orElse(null);
            if (fresh == null || !fresh.isOpen() || fresh.stock() < qty) {
                return Call.fail(409, Call.Reason.OUT_OF_STOCK, fresh);
            }
            last = client.buy(lotId, new BuyRequest(buyerId, qty, fresh.version()));
            if (last.success() || !last.concurrencyRetryable()) {
                return last;
            }
            sleep(2L * (attempt + 1));
        }
        return last == null ? Call.fail(503, Call.Reason.BUSY, null) : last;
    }

    private static void driveSeller(ExchangeClient client, List<String> owned, int threadIndex, Config cfg,
                                    Tally tally, Map<String, Long> lastAckedPrice) {
        if (owned.isEmpty()) {
            return;
        }
        Random random = new Random(500 + threadIndex);
        for (int i = 0; i < cfg.opsPerSeller(); i++) {
            long start = System.nanoTime();
            String lotId = owned.get(random.nextInt(owned.size()));
            LotView before = client.lot(lotId).orElse(null);
            if (before == null) {
                tally.rejected().increment();
                continue;
            }
            long newPrice = Math.max(1, before.price() - 1);
            Call call = client.setPrice(lotId, cfg.useIfMatch()
                    ? new PriceRequest(newPrice, before.version())
                    : PriceRequest.blind(newPrice));
            classify(call, tally);
            if (call.success()) {
                tally.priceWrites().increment();
                lastAckedPrice.put(lotId, newPrice);
            }
            tally.writeMicros().add(micros(start));

            if (cfg.verifyDelayMillis() > 0 && call.success()) {
                sleep(cfg.verifyDelayMillis());
                Long expected = lastAckedPrice.get(lotId);
                LotView now = client.lot(lotId).orElse(null);
                if (expected != null && now != null && now.price() != expected) {
                    tally.priceLost().incrementAndGet();
                }
            }
        }
    }

    /** Выбор лота по {@link Config#pickMode()} — см. комментарий в Config. */
    private static LotView pickLot(List<LotView> lots, Random random, Config cfg) {
        List<LotView> available = lots.stream()
                .filter(LotView::isOpen)
                .filter(l -> l.stock() >= cfg.qtyMin())
                .toList();
        if (available.isEmpty()) {
            return null;
        }
        boolean randomPick = switch (cfg.pickMode()) {
            case "min" -> false;
            case "random" -> true;
            default -> random.nextDouble() < 0.5;
        };
        if (randomPick) {
            return available.get(random.nextInt(available.size()));
        }
        return available.stream().min((x, y) -> Long.compare(x.price(), y.price())).orElse(null);
    }

    private static void classify(Call call, Tally tally) {
        if (call.success()) {
            tally.ok().increment();
        } else if (Call.Reason.CONFLICT.equals(call.reason())) {
            tally.conflict().increment();
        } else if (call.concurrencyRetryable()) {
            tally.busy().increment();
        } else {
            tally.rejected().increment();
        }
    }

    private static String titleOf(ExchangeClient client, String strategy) {
        try {
            return client.catalog().stream()
                    .filter(row -> strategy.equals(row.get("name")))
                    .map(row -> row.get("title"))
                    .findFirst()
                    .orElse(strategy);
        } catch (Exception e) {
            return strategy;
        }
    }

    private static long micros(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000;
    }

    private static double percentile(List<Long> unsortedMicros, double q) {
        List<Long> sorted = new ArrayList<>(unsortedMicros);
        if (sorted.isEmpty()) {
            return 0;
        }
        sorted.sort(Long::compare);
        int index = (int) Math.min(sorted.size() - 1, Math.ceil(q * sorted.size()) - 1);
        return sorted.get(Math.max(0, index));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
