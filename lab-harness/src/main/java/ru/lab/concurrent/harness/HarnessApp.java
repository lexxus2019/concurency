package ru.lab.concurrent.harness;

import ru.lab.concurrent.core.cli.Args;
import ru.lab.concurrent.core.dto.ScenarioRequest;
import ru.lab.concurrent.core.http.ExchangeClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Прогонощик. Главная «демо-машинка» практики: поднимает биржу, прогоняет идентичную
 * нагрузку на каждой стратегии и печатает таблицу «правильность x скорость».
 *
 * <p>Почему это ядро занятия: спорить о том, «какая синхронизация лучше», бесполезно без
 * измерений. Здесь у каждого решения появляется цена, и студенты сами формулируют вывод —
 * обычно первый в жизни вывод о конкурентности, а не заученный.</p>
 *
 * <pre>
 * java -jar lab-harness/target/lab-harness-1.0.0.jar                      # все стратегии, дефолтный сценарий
 * java -jar ... --scenario=hot                                            # один горячий лот — ад оптимистичной блокировки
 * java -jar ... --only=none,sync,optimistic --ops=40                      # быстрый прогон на 1 минуту
 * java -jar ... --server=http://localhost:8080                            # гонять по внешней бирже, не поднимая свою
 * java -jar ... --csv=out/results.csv                                     # выгрузить для отчёта
 * </pre>
 */
public final class HarnessApp {

    public static void main(String[] args) throws Exception {
        Args a = Args.parse(args)
                .explain("server", "внешняя биржа (пусто = поднять свою)", "")
                .explain("port", "порт своей биржи (0 = случайный)", "0")
                .explain("scenario", "default | hot | wide", "default")
                .explain("only", "список стратегий через запятую", "")
                .explain("skip", "исключить стратегии через запятую", "deadlock")
                .explain("threads", "потоков-покупателей", "12")
                .explain("ops", "покупок на поток", "60")
                .explain("seller-threads", "потоков-продавцов", "3")
                .explain("seller-ops", "переоценок на поток продавца", "25")
                .explain("buyer-ids", "сколько id покупателей (меньше потоков = общие кошельки)", "4")
                .explain("qty-min", "единиц в заказе, минимум", "1")
                .explain("qty-max", "единиц в заказе, максимум", "3")
                .explain("read-share", "доля чтений витрины", "0.15")
                .explain("pick-mode", "min | random | mixed — как покупатель выбирает лот", "mixed")
                .explain("hazard", "микросекунд на hazard-точку", "150")
                .explain("ifmatch", "клиенты передают версию (как требует оптимистичная стратегия)", "false")
                .explain("verify-delay-ms", "задержка клиентской проверки записи продавца", "30")
                .explain("repeat", "повторить каждый прогон N раз (медленнее, честнее)", "1")
                .explain("csv", "куда положить CSV", "")
                .explain("list", "показать только каталог стратегий и выйти", "false");

        if (a.helpRequested()) {
            System.out.println("харнес: " + a.usageText());
            return;
        }

        long hazard = a.lng("hazard", 150);
        String external = a.str("server", "");
        ServerHolder holder = external.isBlank() ? ServerHolder.startOwn(a, hazard) : ServerHolder.external(external);
        if (holder == null) {
            System.out.println("не удалось поднять биржу — запусти её сам: java -jar exchange-server/target/*.jar");
            return;
        }
        String base = holder.base();

        try (ExchangeClient client = new ExchangeClient(base)) {
            waitReady(client);
            client.hazardMicros(hazard);
            List<String> available = client.strategies();
            System.out.println("биржа на " + base + ", стратегии: " + available);

            if (a.flag("list", false)) {
                printCatalog(client);
                return;
            }

            List<String> selected = select(available, a.str("only", ""), a.str("skip", "deadlock"));
            if (selected.isEmpty()) {
                System.out.println("нечего прогонять: фильтр не оставил стратегий");
                return;
            }

            LoadRunner.Config cfg = new LoadRunner.Config(
                    scenario(a.str("scenario", "default")),
                    a.integer("threads", 12),
                    a.integer("ops", 60),
                    a.integer("seller-threads", 3),
                    a.integer("seller-ops", 25),
                    a.integer("qty-min", 1),
                    a.integer("qty-max", 3),
                    a.integer("buyer-ids", 4),
                    Double.parseDouble(a.str("read-share", "0.15")),
                    a.flag("ifmatch", false),
                    a.lng("verify-delay-ms", 30),
                    a.str("pick-mode", "mixed"));

            System.out.printf("сценарий=%s, покупатели=%d x %d операций, продавцы=%d x %d, чтений=%.0f%%, hazard=%d мкс, версия=%s%n%n",
                    a.str("scenario", "default"), cfg.buyerThreads(), cfg.opsPerBuyer(),
                    cfg.sellerThreads(), cfg.opsPerSeller(), cfg.readShare() * 100, hazard,
                    cfg.useIfMatch() ? "ifmatch" : "blind");

            List<RunResult> results = new ArrayList<>();
            int repeat = Math.max(1, a.integer("repeat", 1));
            for (String strategy : selected) {
                System.out.println("→ " + strategy + " …");
                RunResult best = null;
                for (int i = 0; i < repeat; i++) {
                    RunResult r = LoadRunner.run(client, strategy, cfg);
                    best = i == 0 ? r : merge(best, r);
                    Map<String, Object> threads = client.threads();
                    if (Boolean.TRUE.equals(threads.get("deadlocked"))) {
                        System.out.println("   ! ВЗАИМОБЛОКИРОВКА: " + threads.get("deadlockedCount")
                                + " потоков заложены. Подробности: GET " + base + "/api/lab/threads");
                    }
                }
                results.add(best);
                System.out.printf("   ok=%d busy=%d conflict=%d инварианты=%s%n",
                        best.ok(), best.busy(), best.conflict(), best.violations());
            }

            Table.print(results);
            printConclusions(results);
            String csv = a.str("csv", "");
            if (!csv.isBlank()) {
                writeCsv(Path.of(csv), results);
                System.out.println("таблица сохранена в " + Path.of(csv).toAbsolutePath());
            }
        } finally {
            holder.close();
        }
    }

    private static ScenarioRequest scenario(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "hot" -> ScenarioRequest.hotLot();
            case "wide" -> new ScenarioRequest(12, 8, 8, 200, 100, 300_000);
            default -> ScenarioRequest.labDefault();
        };
    }

    private static List<String> select(List<String> available, String only, String skip) {
        List<String> out = new ArrayList<>(available);
        if (!only.isBlank()) {
            out.retainAll(List.of(only.split(",")));
        }
        if (!skip.isBlank()) {
            out.removeAll(List.of(skip.split(",")));
        }
        return out;
    }

    /** При повторе оставляем худший по правильности и средний по скорости — честнее оптимистичного «лучшего». */
    private static RunResult merge(RunResult a, RunResult b) {
        RunResult worst = a.clean() && !b.clean() ? b : (!a.clean() && b.clean() ? a : (a.ok() >= b.ok() ? a : b));
        double secs = (a.durationSec() + b.durationSec()) / 2;
        return new RunResult(worst.strategy(), worst.title(), secs,
                avg(a.ok(), b.ok()), avg(a.busy(), b.busy()), avg(a.conflict(), b.conflict()),
                avg(a.rejected(), b.rejected()), avg(a.reads(), b.reads()),
                Math.round((a.opsPerSec() + b.opsPerSec()) / 2),
                (a.p95ReadMs() + b.p95ReadMs()) / 2, (a.p95WriteMs() + b.p95WriteMs()) / 2,
                Math.max(a.maxHoldMicros(), b.maxHoldMicros()), avg(a.retries(), b.retries()),
                avg(a.priceWrites(), b.priceWrites()), avg(a.priceLost(), b.priceLost()),
                worst.violations());
    }

    private static long avg(long a, long b) {
        return (a + b) / 2;
    }

    private static double avg(double a, double b) {
        return (a + b) / 2;
    }

    private static void printCatalog(ExchangeClient client) {
        for (Map<String, String> row : client.catalog()) {
            System.out.printf("%-11s %-38s %s%n", row.get("name"), row.get("title"), row.get("how"));
        }
    }

    /** Мини-выводы под таблицей — то, что студенты потом пересказывают своими словами в отчёте. */
    private static void printConclusions(List<RunResult> results) {
        System.out.println();
        results.stream().filter(r -> !r.clean()).findFirst().ifPresent(r ->
                System.out.println("ВЫВОД 1. Данные портит стратегия '" + r.strategy() + "' (" + r.violations()
                        + "). Отсутствие исключений и 200 OK — НЕ признак корректности."));
        results.stream().filter(RunResult::clean).max((x, y) -> Double.compare(x.opsPerSec(), y.opsPerSec()))
                .ifPresent(r -> System.out.println("ВЫВОД 2. Быстрее всех среди корректных: '" + r.strategy()
                        + "' — " + r.opsPerSec() + " ops/s при p95 записи=" + r.p95WriteMs() + " мс"));
        results.stream().filter(RunResult::clean).min((x, y) -> Double.compare(x.opsPerSec(), y.opsPerSec()))
                .ifPresent(r -> System.out.println("ВЫВОД 3. Медленнее всех среди корректных: '" + r.strategy()
                        + "' — " + r.opsPerSec() + " ops/s. Это цена «самого надёжного» решения"));
        System.out.println("ВЫВОД 4. Правильного ответа в списке нет: выбор — это функция профиля конфликтов,");
        System.out.println("        требуемой задержки и того, кто готов писать код повторов на клиенте.");
    }

    private static void waitReady(ExchangeClient client) throws InterruptedException {
        for (int i = 0; i < 150; i++) {
            if (client.healthy()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("биржа не отвечает на " + client.baseUrl());
    }

    private static void writeCsv(Path path, List<RunResult> results) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("strategy;title;seconds;ok;busy;conflict;rejected;reads;ops_per_sec;")
                .append("p95_read_ms;p95_write_ms;max_hold_micros;retries;price_writes;price_lost;invariants\n");
        for (RunResult r : results) {
            sb.append(r.strategy()).append(';').append(r.title()).append(';')
                    .append(r.durationSec()).append(';').append(r.ok()).append(';').append(r.busy()).append(';')
                    .append(r.conflict()).append(';').append(r.rejected()).append(';').append(r.reads()).append(';')
                    .append(r.opsPerSec()).append(';')
                    .append(round(r.p95ReadMs(), 3)).append(';').append(round(r.p95WriteMs(), 3)).append(';')
                    .append(r.maxHoldMicros()).append(';').append(r.retries()).append(';')
                    .append(r.priceWrites()).append(';').append(r.priceLost()).append(';')
                    .append(r.violations()).append('\n');
        }
        Files.writeString(path, sb.toString());
    }

    private static double round(double v, int digits) {
        double m = Math.pow(10, digits);
        return Math.round(v * m) / m;
    }

    private HarnessApp() {
    }
}
