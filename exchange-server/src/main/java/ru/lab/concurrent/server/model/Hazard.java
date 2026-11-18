package ru.lab.concurrent.server.model;

import java.util.concurrent.locks.LockSupport;

/**
 * Искусственное расширение гонок. Без него лаба не работает как лаба:
 * {@code check-then-act} на современных CPU проигрывается редко, и «правильная на вид»
 * наивная реализация неделю молча портит данные у одного клиента из миллиона.
 *
 * <p>Каждая точка — это замедленная «реальная работа»: расчёт цены, вызов платёжного шлюза,
 * запись в БД. Ставим их ровно там, где в коде есть разрыв между чтением и записью.
 * Держать замок поверх такой точки — само по себе отдельный антипаттерн, который
 * студенты увидят на цифрах throughput.</p>
 *
 * <p>Точки намеренно названы по имени классической аномалии, чтобы в отчёте можно было
 * писать «нарушение возникло в точке B», а не «где-то в buy».</p>
 */
public final class Hazard {

    /** между чтением лота и проверкой остатка: читаем устаревшее (non-repeatable read) */
    public static final String A_READ = "A:read";
    /** между «проверил, что хватает» и «записал» — классический TOCTOU / двойная продажа */
    public static final String B_CHECK_THEN_ACT = "B:check-then-act";
    /** между списанием у покупателя и зачислением продавцу — грязное чтение, деньги «в полёте» */
    public static final String C_SPLIT_TRANSFER = "C:split-transfer";
    /** между записью остатка и записью статуса — состояние рассогласовано на время точки */
    public static final String D_FIELD_WINDOW = "D:field-window";

    private static final long DEFAULT_MICROS = 150;
    private static volatile long micros = DEFAULT_MICROS;

    private Hazard() {
    }

    public static void setMicros(long value) {
        micros = Math.max(0, value);
    }

    public static long micros() {
        return micros;
    }

    /** Имитация медленной работы в критической зоне. */
    public static void at(String point) {
        long n = micros;
        if (n <= 0) {
            Thread.onSpinWait();
            return;
        }
        // parkNanos даёт~разрешение 50-100 мкс на Windows, поэтому для tiny значений
        // точность низкая — но нам важна не точность, а сам факт окна конкуренции
        LockSupport.parkNanos(n * 1_000);
    }

    public static String describe(String point) {
        return point + " (" + micros + " мкс)";
    }
}
