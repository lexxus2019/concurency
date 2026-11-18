package ru.lab.concurrent.buyer;

import ru.lab.concurrent.core.check.Invariants;
import ru.lab.concurrent.core.check.Violation;
import ru.lab.concurrent.core.cli.Args;
import ru.lab.concurrent.core.dto.BuyRequest;
import ru.lab.concurrent.core.dto.Call;
import ru.lab.concurrent.core.dto.LotView;
import ru.lab.concurrent.core.dto.ScenarioRequest;
import ru.lab.concurrent.core.dto.StateView;
import ru.lab.concurrent.core.http.ExchangeClient;
import ru.lab.concurrent.core.http.RetryPolicy;

import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Клиент-покупатель. Отдельный процесс, у него нет доступа к состоянию биржи — только HTTP.
 *
 * <p>Зачем он в лабе: поведение клиента — это вторая половина стратегии синхронизации.
 * Сервер может «правильно» ответить 503 BUSY, но если клиент не умеет повторять с backoff,
 * эти отказы превратятся в потерянные заказы. И наоборот: безрассудный retry на 409
 * INSUFFICIENT_FUNDS превращает лабу в livelock. Поэтому в клиенте есть осознанный выбор:</p>
 * <ul>
 *   <li>{@code --version-mode=blind} — писать «вслепую», не передавать If-Match (дефолт лабы);</li>
 *   <li>{@code --version-mode=ifmatch} — перечитывать лот и отправлять его версию, как требует
 *       оптимистичная стратегия;</li>
 *   <li>{@code --retries}/{@code --backoff} — политика повторов.</li>
 * </ul>
 */
public final class BuyerClientApp {

    /** Как выбираем лот: min — все в самый дешёвый, random — растекание по витрине, mixed — как в жизни. */
    private static String pickMode = "mixed";

    public static void main(String[] args) throws Exception {
        Args a = Args.parse(args)
                .explain("server", "адрес биржи", "http://localhost:8080")
                .explain("threads", "сколько одновременных покупателей", "4")
                .explain("buyers", "сколько id покупателей используем (меньше потоков = один "
                        + "покупатель из нескольких потоков — ловит дыру ключа «по лоту»)", "4")
                .explain("ops", "сколько покупок на поток", "150")
                .explain("qty-min", "минимум единиц в заказе", "1")
                .explain("qty-max", "максимум единиц в заказе", "3")
                .explain("read-share", "доля операций чтения витрины", "0.15")
                .explain("pick-mode", "min | random | mixed — как выбирать лот", "mixed")
                .explain("version-mode", "blind | ifmatch", "blind")
                .explain("retries", "попыток на конкурентный отказ", "6")
                .explain("backoff", "базовый backoff, мс", "5")
                .explain("reset", "сбросить мир перед прогоном", "true")
                .explain("quiet", "не печатать прогресс", "false");

        if (a.helpRequested()) {
            System.out.println("покупатель: " + a.usageText());
            return;
        }

        String server = a.str("server", "http://localhost:8080");
        int threads = a.integer("threads", 4);
        int buyerIds = Math.max(1, a.integer("buyers", 4));
        int ops = a.integer("ops", 150);
        int qtyMin = a.integer("qty-min", 1);
        int qtyMax = Math.max(qtyMin, a.integer("qty-max", 3));
        double readShare = Math.max(0, Math.min(0.95, Double.parseDouble(a.str("read-share", "0.15"))));
        pickMode = a.str("pick-mode", "mixed");
        boolean ifMatch = "ifmatch".equalsIgnoreCase(a.str("version-mode", "blind"));
        RetryPolicy retry = new RetryPolicy(a.integer("retries", 6), a.lng("backoff", 5), 2.0, 250, ifMatch);
        boolean quiet = a.flag("quiet", false);

        try (ExchangeClient client = new ExchangeClient(server)) {
            waitReady(client);
            if (a.flag("reset", true)) {
                client.reset(ScenarioRequest.labDefault());
            }
            System.out.println("покупатель: стартуем против " + server + ", потоков=" + threads
                    + ", id покупателей=" + buyerIds + ", режим версии=" + (ifMatch ? "ifmatch" : "blind")
                    + "\nпокупатель: политика повторов: " + retry.describe());

            LongAdder ok = new LongAdder();
            LongAdder busy = new LongAdder();
            LongAdder conflict = new LongAdder();
            LongAdder rejected = new LongAdder();
            LongAdder spent = new LongAdder();
            AtomicLong retriesUsed = new AtomicLong();
            AtomicLong maxAttempts = new AtomicLong(1);

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            long startedAt = System.nanoTime();
            for (int t = 0; t < threads; t++) {
                final String buyerId = "buyer-" + (1 + (t % buyerIds));
                final Random random = new Random(42 + t);
                pool.submit(() -> run(buyerId, ops, qtyMin, qtyMax, readShare, ifMatch, retry, client,
                        ok, busy, conflict, rejected, spent, retriesUsed, maxAttempts, random, quiet));
            }
            pool.shutdown();
            if (!pool.awaitTermination(5, TimeUnit.MINUTES)) {
                System.out.println("покупатель: не дождались завершения — сервис, похоже, завис (смотри /api/lab/threads)");
                pool.shutdownNow();
            }
            double seconds = (System.nanoTime() - startedAt) / 1e9;

            System.out.printf("%nпокупатель: итог за %.2f с — успех=%d, busy/timeout=%d, conflict=%d, бизнес-отказ=%d,%n",
                    seconds, ok.sum(), busy.sum(), conflict.sum(), rejected.sum());
            System.out.printf("покупатель: потрачено %d у.е., повторов %d, максимум попыток на операцию %d, скорость %.0f заказ/с%n",
                    spent.sum(), retriesUsed.get(), maxAttempts.get(), (ok.sum() + busy.sum() + conflict.sum() + rejected.sum()) / seconds);
            report(client);
        }
    }

    private static void run(String buyerId, int ops, int qtyMin, int qtyMax, double readShare, boolean ifMatch,
                            RetryPolicy retry, ExchangeClient client, LongAdder ok, LongAdder busy,
                            LongAdder conflict, LongAdder rejected, LongAdder spent, AtomicLong retriesUsed,
                            AtomicLong maxAttempts, Random random, boolean quiet) {
        for (int i = 0; i < ops; i++) {
            List<LotView> lots = client.lots();
            if (random.nextDouble() < readShare) {
                // витрина: только чтение — по нему видно, дорого ли стратегии читающим
                continue;
            }
            LotView pick = pickLot(lots, random, qtyMin, pickMode);
            if (pick == null) {
                rejected.increment();
                continue;
            }
            int qty = qtyMin + (qtyMax > qtyMin ? random.nextInt(qtyMax - qtyMin + 1) : 0);
            long[] attempts = {0};
            Call call = retry.run("buy", () -> {
                attempts[0]++;
                long version = ifMatch ? reloadVersion(client, pick.id()) : BuyRequest.IGNORE_VERSION;
                return client.buy(pick.id(), new BuyRequest(buyerId, qty, version));
            }, () -> reloadVersion(client, pick.id()));
            maxAttempts.accumulateAndGet(attempts[0], Math::max);
            if (attempts[0] > 1) {
                retriesUsed.addAndGet(attempts[0] - 1);
            }
            if (call.success()) {
                ok.increment();
                spent.add(call.lot() == null ? 0 : (long) qty * priceOf(call.lot()));
            } else if (Call.Reason.CONFLICT.equals(call.reason())) {
                conflict.increment();
            } else if (call.concurrencyRetryable()) {
                busy.increment();
            } else {
                rejected.increment();
            }
            if (!quiet && i % 50 == 0) {
                System.out.printf("[%s] %d/%d операций, успех=%d busy=%d%n",
                        buyerId, i, ops, ok.sum(), busy.sum());
            }
        }
    }

    /** Выбор лота: профиль выбора решает, сталкиваются ли клиенты на одном ресурсе или нет. */
    private static LotView pickLot(List<LotView> lots, Random random, int qtyMin, String mode) {
        List<LotView> available = lots.stream()
                .filter(LotView::isOpen)
                .filter(l -> l.stock() >= qtyMin)
                .toList();
        if (available.isEmpty()) {
            return null;
        }
        boolean randomPick = switch (mode) {
            case "min" -> false;
            case "random" -> true;
            default -> random.nextDouble() < 0.5;
        };
        if (randomPick) {
            return available.get(random.nextInt(available.size()));
        }
        return available.stream().min((x, y) -> Long.compare(x.price(), y.price())).orElse(null);
    }

    private static long priceOf(LotView lot) {
        return lot.price();
    }

    private static long reloadVersion(ExchangeClient client, String lotId) {
        return client.lot(lotId).map(LotView::version).orElse(-1L);
    }

    private static void waitReady(ExchangeClient client) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (client.healthy()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("биржа не отвечает на " + client.baseUrl() + " — запусти exchange-server");
    }

    /** Клиент сам проверяет, во что вылился прогон: «я же не обязан верить сервису на слово». */
    private static void report(ExchangeClient client) {
        StateView state = client.state();
        List<Violation> violations = Invariants.check(state);
        System.out.println("покупатель: инварианты после прогона = " + Invariants.summarize(violations)
                + " (стратегия '" + state.stats().strategy() + "')");
        if (!violations.isEmpty()) {
            violations.forEach(v -> System.out.println("   ! " + v));
        }
    }

    private BuyerClientApp() {
    }
}
