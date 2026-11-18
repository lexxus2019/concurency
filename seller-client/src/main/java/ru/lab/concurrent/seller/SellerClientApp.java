package ru.lab.concurrent.seller;

import ru.lab.concurrent.core.check.Invariants;
import ru.lab.concurrent.core.check.Violation;
import ru.lab.concurrent.core.cli.Args;
import ru.lab.concurrent.core.dto.BuyRequest;
import ru.lab.concurrent.core.dto.Call;
import ru.lab.concurrent.core.dto.CreateLotRequest;
import ru.lab.concurrent.core.dto.LotView;
import ru.lab.concurrent.core.dto.PriceRequest;
import ru.lab.concurrent.core.dto.ScenarioRequest;
import ru.lab.concurrent.core.dto.StateView;
import ru.lab.concurrent.core.http.ExchangeClient;
import ru.lab.concurrent.core.http.RetryPolicy;

import java.util.ArrayList;
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
 * Клиент-продавец. Роль не «зеркальная» покупателю, а принципиально другая — поэтому
 * в лабе два разных процесса, а не один с флагом:
 *
 * <ol>
 *   <li>продавец ПИШЕТ то, чего покупатель не трогает: цену и статус лота. Значит продавец
 *       может быть <b>детектором потерянных обновлений</b>: если после 200 OK на price=97
 *       через мгновение читается 95 — значит параллельная покупка записала лот поверх нашего
 *       подтверждения (в наивной реализации покупка пересчитывает лот из устаревшего чтения).
 *       У покупателя такой способности нет: для него цена — внешняя величина;</li>
 *   <li>продавец создаёт новые лоты, то есть меняет набор ресурсов во время прогона, и отзывает
 *       старые — он меняет сам «размер проблемы», с которым борется синхронизация;</li>
 *   <li>операции продавца реже, но держат ресурс дороже: по {@code maxHoldMicros} видно,
 *       что именно редкие записи отравляют жизнь частым покупкам.</li>
 * </ol>
 *
 * <p>Ключ {@code --verify=true} включает клиентскую проверку подтверждённых записей. Чтобы она
 * была корректной, каждый лот переоценивает ровно один поток (лоты поделены между потоками):
 * иначе два наших потока сами затрут друг друга, и мы начнём обвинять сервер в своём баге.
 * Отличная иллюстрация того, что «детектор» надо строить вместе с нагрузкой, а не после.</p>
 */
public final class SellerClientApp {

    public static void main(String[] args) throws Exception {
        Args a = Args.parse(args)
                .explain("server", "адрес биржи", "http://localhost:8080")
                .explain("seller", "id продавца", "seller-U1")
                .explain("post", "сколько лотов выставить в начале", "2")
                .explain("stock", "остаток в новых лотах", "300")
                .explain("price", "цена в новых лотах", "120")
                .explain("ops", "сколько циклов переоценки всего", "120")
                .explain("threads", "сколько потоков переоценивает", "2")
                .explain("discount", "на сколько уцениваем за шаг (от увиденного)", "1")
                .explain("cancel-prob", "вероятность отозвать лот", "0.01")
                .explain("version-mode", "blind | ifmatch", "blind")
                .explain("verify", "проверять, что подтверждённая цена уцелела", "true")
                .explain("verify-delay-ms", "задержка проверки, мс", "30")
                .explain("retries", "попыток на конкурентный отказ", "5")
                .explain("backoff", "базовый backoff, мс", "5")
                .explain("reset", "сбросить мир перед прогоном", "false")
                .explain("quiet", "не печатать прогресс", "false");

        if (a.helpRequested()) {
            System.out.println("продавец: " + a.usageText());
            return;
        }

        String server = a.str("server", "http://localhost:8080");
        String sellerId = a.str("seller", "seller-U1");
        int toPost = a.integer("post", 2);
        int stock = a.integer("stock", 300);
        long price = a.lng("price", 120);
        int ops = a.integer("ops", 120);
        int threads = Math.max(1, a.integer("threads", 2));
        int discount = a.integer("discount", 1);
        double cancelProb = Double.parseDouble(a.str("cancel-prob", "0.01"));
        boolean ifMatch = "ifmatch".equalsIgnoreCase(a.str("version-mode", "blind"));
        boolean verify = a.flag("verify", true);
        long verifyDelay = a.lng("verify-delay-ms", 30);
        boolean quiet = a.flag("quiet", false);
        RetryPolicy retry = new RetryPolicy(a.integer("retries", 5), a.lng("backoff", 5), 2.0, 200, false);

        try (ExchangeClient client = new ExchangeClient(server)) {
            waitReady(client);
            if (a.flag("reset", false)) {
                client.reset(ScenarioRequest.labDefault());
            }
            System.out.println("продавец: " + sellerId + " против " + server
                    + ", потоков=" + threads + ", режим версии=" + (ifMatch ? "ifmatch" : "blind")
                    + ", проверка подтверждённых цен=" + (verify ? "вкл" : "выкл"));

            List<String> myLots = new ArrayList<>();
            for (int i = 0; i < toPost; i++) {
                Call created = client.createLot(new CreateLotRequest(sellerId, sellerId + "-лот-" + (i + 1), price, stock));
                if (created.success() && created.lot() != null) {
                    myLots.add(created.lot().id());
                    System.out.println("продавец: выставил " + created.lot().id() + ", stock=" + stock + ", price=" + price);
                } else {
                    System.out.println("продавец: лот не выставлен: " + created.reason());
                }
            }
            if (myLots.isEmpty()) {
                System.out.println("продавец: нечего переоценивать, выходим");
                return;
            }

            LongAdder ok = new LongAdder();
            LongAdder busy = new LongAdder();
            LongAdder conflict = new LongAdder();
            LongAdder rejected = new LongAdder();
            LongAdder cancelled = new LongAdder();
            AtomicLong lostWrites = new AtomicLong();
            AtomicLong checkedWrites = new AtomicLong();
            /** lotId → цена, которую нам последний раз подтвердили (свой лот пишут, значит запись одна). */
            Map<String, Long> lastAckedPrice = new ConcurrentHashMap<>();

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            long startedAt = System.nanoTime();
            for (int t = 0; t < threads; t++) {
                // делим лоты между потоками: у каждого лота ровно один писатель цены
                final List<String> owned = new ArrayList<>();
                for (int i = 0; i < myLots.size(); i++) {
                    if (i % threads == t) {
                        owned.add(myLots.get(i));
                    }
                }
                if (owned.isEmpty()) {
                    continue;
                }
                final int threadIndex = t;
                pool.submit(() -> {
                    Random random = new Random(1000 + threadIndex);
                    int perThread = Math.max(1, ops / threads);
                    for (int i = 0; i < perThread; i++) {
                        String lotId = owned.get(random.nextInt(owned.size()));
                        LotView before = client.lot(lotId).orElse(null);
                        if (before == null) {
                            rejected.increment();
                            continue;
                        }
                        long newPrice = Math.max(1, before.price() - discount);
                        Call call = retry.run("price", () -> {
                            long version = ifMatch ? client.lot(lotId).map(LotView::version).orElse(-1L)
                                    : BuyRequest.IGNORE_VERSION;
                            return client.setPrice(lotId, new PriceRequest(newPrice, version));
                        }, null);

                        if (call.success()) {
                            ok.increment();
                            lastAckedPrice.put(lotId, newPrice);
                        } else if (Call.Reason.CONFLICT.equals(call.reason())) {
                            conflict.increment();
                        } else if (call.concurrencyRetryable()) {
                            busy.increment();
                        } else {
                            rejected.increment();
                        }

                        if (random.nextDouble() < cancelProb) {
                            Call cancel = client.cancel(lotId);
                            if (cancel.success()) {
                                cancelled.increment();
                                System.out.println("продавец: отозвал " + lotId);
                            }
                        }

                        if (verify) {
                            sleep(verifyDelay);
                            Long expected = lastAckedPrice.get(lotId);
                            LotView now = client.lot(lotId).orElse(null);
                            if (expected != null && now != null) {
                                checkedWrites.incrementAndGet();
                                if (now.price() != expected) {
                                    long n = lostWrites.incrementAndGet();
                                    if (n <= 5) {
                                        System.out.printf("продавец: ПОТЕРЯНА ПОДТВЕРЖДЁННАЯ ЗАПИСЬ: мне был 200 OK на price=%d,"
                                                        + " а сейчас читается price=%d (версия %d)%n",
                                                expected, now.price(), now.version());
                                    }
                                }
                            }
                        }
                        if (!quiet && i % 40 == 0) {
                            System.out.printf("[продавец-%d] %d/%d, успех=%d busy=%d conflict=%d%n",
                                    threadIndex, i, perThread, ok.sum(), busy.sum(), conflict.sum());
                        }
                    }
                });
            }
            pool.shutdown();
            if (!pool.awaitTermination(5, TimeUnit.MINUTES)) {
                System.out.println("продавец: поток не завершился — похоже на взаимоблокировку, смотри GET /api/lab/threads");
                pool.shutdownNow();
            }
            double seconds = (System.nanoTime() - startedAt) / 1e9;

            System.out.printf("%nпродавец: итог за %.2f с — успех=%d, busy=%d, conflict=%d, отказ=%d, отозвано=%d%n",
                    seconds, ok.sum(), busy.sum(), conflict.sum(), rejected.sum(), cancelled.sum());
            if (verify) {
                System.out.printf("продавец: проверено подтверждённых записей %d, затёрто %d (%.1f%%)%n",
                        checkedWrites.get(), lostWrites.get(),
                        checkedWrites.get() == 0 ? 0 : 100.0 * lostWrites.get() / checkedWrites.get());
            }
            StateView state = client.state();
            List<Violation> violations = Invariants.check(state);
            System.out.println("продавец: инварианты сервиса = " + Invariants.summarize(violations)
                    + " (стратегия '" + state.stats().strategy() + "')");
            violations.forEach(v -> System.out.println("   ! " + v));
        }
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void waitReady(ExchangeClient client) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (client.healthy()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("биржа не отвечает на " + client.baseUrl());
    }

    private SellerClientApp() {
    }
}
