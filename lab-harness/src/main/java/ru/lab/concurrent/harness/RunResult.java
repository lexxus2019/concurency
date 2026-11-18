package ru.lab.concurrent.harness;

/**
 * Одна строка итогового отчёта харнеса. Все поля числовые, чтобы таблицу можно было
 * сравнивать между прогонами и прикладывать к отчёту студента.
 *
 * <p>Задержки разделены на «чтения» и «записи» нарочно: средняя задержка по всем запросам
 * враньё, потому что в миксе 70% дешёвых GET тянут медиану вниз, и стратегия, которая
 * убивает только покупки, выглядит неприлично хорошо.</p>
 *
 * @param strategy       ключ стратегии
 * @param title          человекочитаемое название
 * @param durationSec    длительность прогона
 * @param ok             применено операций (клиентская статистика)
 * @param busy           получено 503 BUSY/TIMEOUT/QUEUE_FULL
 * @param conflict       получено 409 CONFLICT (условная запись/CAS)
 * @param rejected       бизнес-отказ (нет товара/денег)
 * @param reads          выполнено операций чтения
 * @param opsPerSec      throughput по применённым операциям
 * @param p95ReadMs      95-я перцентиль задержки чтения
 * @param p95WriteMs     95-я перцентиль задержки записи (покупки/цены)
 * @param maxHoldMicros  самое долгое удержание критической секции на сервере
 * @param retries        повторов CAS на сервере
 * @param priceWrites    подтверждённых записей цены продавца
 * @param priceLost      из них затёрто (клиентский детектор lost update)
 * @param violations     сводка нарушений инвариантов (OK = чисто)
 */
public record RunResult(
        String strategy,
        String title,
        double durationSec,
        long ok,
        long busy,
        long conflict,
        long rejected,
        long reads,
        double opsPerSec,
        double p95ReadMs,
        double p95WriteMs,
        long maxHoldMicros,
        long retries,
        long priceWrites,
        long priceLost,
        String violations) {

    public boolean clean() {
        return "OK".equals(violations);
    }
}
