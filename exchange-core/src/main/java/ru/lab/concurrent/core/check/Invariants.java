package ru.lab.concurrent.core.check;

import ru.lab.concurrent.core.dto.LotView;
import ru.lab.concurrent.core.dto.StateView;

import java.util.ArrayList;
import java.util.List;

/**
 * Чистая функция: снимок состояния → список нарушений. Никакого состояния, никаких замков —
 * поэтому проверку можно запускать хоть из клиента, хоть из харнеса, хоть из теста.
 *
 * <p>Это «опракл» лабы. Студенты должны понять важную вещь: <b>правильность конкурентного кода
 * измеряется инвариантами, а не отсутствием исключений</b>. Сервис в Broken-режиме ни разу
 * не бросит исключения — он молча продаст больше, чем есть.</p>
 */
public final class Invariants {

    public static final String OVERSELL = "OVERSELL";
    public static final String LOST_UNITS = "LOST_UNITS";
    public static final String NEGATIVE_BALANCE = "NEGATIVE_BALANCE";
    public static final String MONEY_LOST = "MONEY_LOST";
    public static final String MONEY_CREATED = "MONEY_CREATED";
    public static final String STOCK_LOST = "STOCK_LOST";
    public static final String STOCK_CREATED = "STOCK_CREATED";
    public static final String STATUS_INCONSISTENT = "STATUS_INCONSISTENT";
    public static final String LOST_UPDATE = "LOST_UPDATE";

    private Invariants() {
    }

    public static List<Violation> check(StateView state) {
        List<Violation> out = new ArrayList<>();

        long oversellUnits = 0;
        int oversellLots = 0;
        long mismatchUnits = 0;
        int mismatchLots = 0;
        int badStatus = 0;
        for (LotView lot : state.lots()) {
            int initial = state.initialStock().getOrDefault(lot.id(), 0);
            long acked = state.ackedUnits().getOrDefault(lot.id(), 0L);
            if (acked > initial) {
                oversellUnits += acked - initial;
                oversellLots++;
            }
            if (lot.stock() < 0 || lot.stock() + lot.sold() != initial) {
                mismatchUnits += Math.abs(initial - (lot.stock() + lot.sold()));
                mismatchLots++;
            }
            if (LotView.SOLD_OUT.equals(lot.status()) && lot.stock() > 0) {
                badStatus++;
            }
            if (LotView.CANCELLED.equals(lot.status()) && lot.stock() > 0 && lot.sold() > 0) {
                badStatus++;
            }
        }
        if (oversellUnits > 0) {
            out.add(new Violation(OVERSELL, (int) oversellUnits,
                    "продано на " + oversellUnits + " единиц больше, чем было на складе (" + oversellLots
                            + " лотов): покупка проверила остаток и записала его не атомарно"));
        }
        if (mismatchLots > 0) {
            out.add(new Violation(LOST_UNITS, (int) mismatchUnits,
                    "учёт расходится со складом на " + mismatchUnits + " единиц (" + mismatchLots
                            + " лотов): stock+sold ≠ начальный остаток, то есть параллельные записи посчитаны от устаревшего чтения"));
        }
        if (badStatus > 0) {
            out.add(new Violation(STATUS_INCONSISTENT, badStatus,
                    "статус противоречит полям (SOLD_OUT при stock>0 и т.п.) — состояние рассогласовано"));
        }

        long moneyNow = state.moneyNow();
        long deltaMoney = moneyNow - state.moneyTotalAtStart();
        if (deltaMoney < 0) {
            out.add(new Violation(MONEY_LOST, 1,
                    "в системе пропало денег на " + deltaMoney + " у.е. (деньги «в полёте» попали в снимок)"));
        } else if (deltaMoney > 0) {
            out.add(new Violation(MONEY_CREATED, 1,
                    "в системе появилось лишних " + deltaMoney + " у.е. (двойное зачисление выручки)"));
        }

        long stockNow = state.stockNow();
        long deltaStock = stockNow - state.stockTotalAtStart();
        if (deltaStock < 0) {
            out.add(new Violation(STOCK_LOST, 1,
                    "товар исчез: stock+sold стало " + stockNow + " вместо " + state.stockTotalAtStart()
                            + " — два покупателя записали поверх друг друга"));
        } else if (deltaStock > 0) {
            out.add(new Violation(STOCK_CREATED, 1,
                    "товар появился из воздуха: " + stockNow + " вместо " + state.stockTotalAtStart()));
        }

        int negative = 0;
        for (var e : state.buyerBalances().entrySet()) {
            if (e.getValue() < 0) {
                negative++;
            }
        }
        if (negative > 0) {
            out.add(new Violation(NEGATIVE_BALANCE, negative,
                    "у " + negative + " покупателей баланс ушёл в минус — проверка баланса была вне атомарной зоны"));
        }

        // подтвердили клиенту N записей, а в данных отразилось M < N → (N - M) записей затёрто
        long acked = state.ackedTotal();
        long versions = state.versionTotal();
        if (acked > versions) {
            out.add(new Violation(LOST_UPDATE, (int) (acked - versions),
                    "подтверждено " + acked + " записей, а в состоянии осталось " + versions
                            + " — часть подтверждённых изменений затёрта параллельной записью"));
        }
        return out;
    }

    public static boolean clean(List<Violation> violations) {
        return violations.isEmpty();
    }

    /** Одна строка для логов клиентов: «INVARIANTS OK» либо перечень кодов. */
    public static String summarize(List<Violation> violations) {
        if (violations.isEmpty()) {
            return "OK";
        }
        return violations.stream().map(v -> v.code() + "x" + v.count()).reduce((a, b) -> a + ", " + b).orElse("OK");
    }
}
