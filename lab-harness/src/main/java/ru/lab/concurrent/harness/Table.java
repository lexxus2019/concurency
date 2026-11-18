package ru.lab.concurrent.harness;

import java.util.List;
import java.util.Locale;

/**
 * Таблица результатов — главный артефакт практики, именно её вставляют в отчёт.
 *
 * <p>Порядок колонок выбран так, чтобы вывод читался слева направо: сначала «правильно ли»,
 * потом «сколько это стоит». Поэтому {@code инварианты} — первая цифровая колонка после имени.</p>
 */
final class Table {

    private Table() {
    }

    private static final String FMT = "%-11s %5.2f %7.0f %6d %8.2f %8.2f %6d %8d %8d %9d %5d %7d  %s%n";

    static void print(List<RunResult> results) {
        String header = String.format(Locale.ROOT,
                "%-11s %5s %7s %6s %8s %8s %6s %8s %8s %9s %5s %7s  %s",
                "стратегия", "сек", "ops/s", "чит.вс", "p95чит,мс", "p95зап,мс", "busy", "conflict",
                "retries", "hold,мкс", "цен", "затёрто", "инварианты");
        System.out.println();
        System.out.println("=".repeat(150));
        System.out.println(header);
        System.out.println("-".repeat(150));
        for (RunResult r : results) {
            System.out.printf(Locale.ROOT, FMT,
                    r.strategy(), r.durationSec(), r.opsPerSec(), r.reads(), r.p95ReadMs(), r.p95WriteMs(),
                    r.busy(), r.conflict(), r.retries(), r.maxHoldMicros(), r.priceWrites(), r.priceLost(),
                    r.clean() ? "OK" : r.violations());
        }
        System.out.println("=".repeat(150));
        System.out.println(" busy      — отказ «занято» (503 BUSY/TIMEOUT/QUEUE_FULL): клиент обязан повторить с backoff");
        System.out.println(" conflict  — версия не совпала (409): клиент обязан ПЕРЕЧИТАТЬ и повторить");
        System.out.println(" retries   — повторов CAS на сервере (внутренняя цена оптимистичной стратегии)");
        System.out.println(" hold      — самое долгое удержание критической секции, мкс");
        System.out.println(" затёрто   — подтверждённых записей цены, которые исчезли (детектор клиента-продавца)");
        System.out.println(" инварианты — что сломалось по версии сервиса; OK = данных не тронули");
    }
}
