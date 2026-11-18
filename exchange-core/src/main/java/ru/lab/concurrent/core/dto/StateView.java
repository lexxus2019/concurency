package ru.lab.concurrent.core.dto;

import java.util.List;
import java.util.Map;

/**
 * Полный снимок состояния биржи одним запросом ({@code GET /api/lab/state}).
 *
 * <p>Пока клиент получил этот снимок, он уже устарел — но именно по нему
 * {@link ru.lab.concurrent.core.check.Invariants} решает, сломалась ли стратегия.
 * Для «грязного чтения» важно, что снимок собирается <b>не атомарно</b>:
 * в Broken-стратегии деньги между «списали у покупателя» и «зачислили продавцу»
 * физически отсутствуют в системе, и снимок это ловит.</p>
 *
 * @param lots            все лоты
 * @param buyerBalances   баланс каждого покупателя
 * @param sellerRevenue   заработок каждого продавца
 * @param moneyTotalAtStart сколько денег было в системе сразу после reset
 * @param stockTotalAtStart сколько товара было в системе сразу после reset
 * @param ackedWrites     lotId → сколько записей в этот лот сервис подтвердил клиентам.
 *                        Если сумма подтверждённых записей больше суммы {@code version} по лотам —
 *                        часть подтверждённых записей была затёрта (lost update).
 * @param ackedUnits      lotId → сколько ЕДИНИЦ товара продано по подтверждениям. Больше
 *                        {@code initialStock} — это и есть oversell: продали то, чего нет.
 * @param initialStock    lotId → начальный остаток лота, эталон для учёта.
 * @param stats           метрики сервиса
 */
public record StateView(
        List<LotView> lots,
        Map<String, Long> buyerBalances,
        Map<String, Long> sellerRevenue,
        long moneyTotalAtStart,
        long stockTotalAtStart,
        Map<String, Long> ackedWrites,
        Map<String, Long> ackedUnits,
        Map<String, Integer> initialStock,
        StatsView stats) {

    public long moneyNow() {
        return buyerBalances.values().stream().mapToLong(Long::longValue).sum()
                + sellerRevenue.values().stream().mapToLong(Long::longValue).sum();
    }

    public long stockNow() {
        return lots.stream().mapToLong(LotView::stock).sum() + lots.stream().mapToLong(LotView::sold).sum();
    }

    public int lotCount() {
        return lots.size();
    }

    /** Сумма версий по всем лотам = сколько записей реально отразилось в данных. */
    public long versionTotal() {
        return lots.stream().mapToLong(LotView::version).sum();
    }

    /** Сколько записей подтверждено клиентам. */
    public long ackedTotal() {
        return ackedWrites.values().stream().mapToLong(Long::longValue).sum();
    }
}
