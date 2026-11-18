package ru.lab.concurrent.server.engine;

import ru.lab.concurrent.core.dto.Call;
import ru.lab.concurrent.core.dto.LotView;
import ru.lab.concurrent.server.model.Book;
import ru.lab.concurrent.server.model.Hazard;
import ru.lab.concurrent.server.model.LotV;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Чистая бизнес-логика биржи — БЕЗ какой-либо синхронизации. Реализация одна на все стратегии.
 *
 * <p>Это ключевой приём лабы: логику мы пишем один раз, а спорим только о том,
 * <b>как</b> её обернуть. Так видно, что «добавить synchronized» и «добавить CAS» — это
 * изменения вокруг одного и того же кода, а не переписывание предметной области.</p>
 *
 * <p>Каждый метод написан как цепочка «прочитал → проверил → записал», и между этими шагами
 * стоят {@link Hazard}-точки. В таком виде методы потокоНЕбезопасны, и это единственная
 * причина всех багов в лабе. Опасные места помечены комментариями {@code // РИСК:} —
 * именно их студентам и нужно закрывать.</p>
 */
public final class Transactions {

    /**
     * Результат попытки применить транзакцию.
     *
     * @param applied     применялась ли запись (false — бизнес-отказ или проигравший CAS)
     * @param casConflict true — запись не удалась только потому, что данные успели измениться;
     *                    стратегия обязана повторить попытку, а не отдать ошибку клиенту
     * @param call        то, что уйдёт клиенту, если решение окончательное
     * @param lot         состояние лота после попытки
     */
    public record Outcome(boolean applied, boolean casConflict, Call call, LotV lot) {

        public static Outcome applied(LotV lot) {
            return new Outcome(true, false, Call.ok(lot.toView()), lot);
        }

        public static Outcome rejected(String reason, int httpStatus, LotV lot) {
            return new Outcome(false, false,
                    Call.fail(httpStatus, reason, lot == null ? null : lot.toView()), lot);
        }

        public static Outcome retryNeeded() {
            return new Outcome(false, true, null, null);
        }
    }

    private Transactions() {
    }

    // -------------------------------------- покупка ---------------------------------

    /**
     * Покупка: списать деньги у покупателя, начислить продавцу, уменьшить остаток лота.
     * Четыре точки, в каждой из которых можно сломаться, — это и есть программа лабы.
     */
    public static Outcome buy(Book book, String lotId, String buyerId, int qty, long expectedVersion) {
        AtomicReference<LotV> ref = book.lotRef(lotId);
        if (ref == null) {
            return Outcome.rejected(Call.Reason.NOT_FOUND, 404, null);
        }
        LotV lot = ref.get();

        Hazard.at(Hazard.A_READ);                       // РИСК: к этому моменту lot мог устареть

        if (lot == null) {
            return Outcome.rejected(Call.Reason.NOT_FOUND, 404, null);
        }
        if (!lot.isOpen()) {
            return Outcome.rejected(soldOutOrCancelled(lot), 409, lot);
        }
        if (qty <= 0) {
            return Outcome.rejected(Call.Reason.OUT_OF_STOCK, 400, lot);
        }
        if (expectedVersion >= 0 && expectedVersion != lot.version()) {
            // условная запись: клиент сам попросил «писать, только если версия та»
            return Outcome.rejected(Call.Reason.CONFLICT, 409, lot);
        }

        long cost = lot.price() * qty;
        AtomicLong buyerWallet = book.buyerWallet(buyerId);
        long balance = buyerWallet.get();
        if (balance < cost) {
            return Outcome.rejected(Call.Reason.INSUFFICIENT_FUNDS, 409, lot);
        }

        Hazard.at(Hazard.B_CHECK_THEN_ACT);             // РИСК: между проверкой и записью — целая жизнь
        if (lot.stock() < qty) {
            return Outcome.rejected(Call.Reason.OUT_OF_STOCK, 409, lot);
        }

        buyerWallet.set(balance - cost);                // РИСК: запись поверх прочитанного значения
        Hazard.at(Hazard.C_SPLIT_TRANSFER);             // РИСК: деньги уже не у покупателя, но ещё не у продавца

        AtomicLong sellerWallet = book.sellerWallet(lot.sellerId());
        long earned = sellerWallet.get();
        sellerWallet.set(earned + cost);                // РИСК: тоже поверх прочитанного

        LotV next = lot.afterBuy(qty);                  // РИСК: afterBuy посчитан от СТАРОГО lot
        Hazard.at(Hazard.D_FIELD_WINDOW);               // РИСК: остаток ещё не записан, а деньги уже ходят
        ref.set(next);                                  // РИСК: set вместо CAS — чужая запись затирается
        book.recordAckedWrite(lotId);
        book.recordAckedUnits(lotId, qty);              // подтверждаем продажу: по этому считается oversell
        return Outcome.applied(next);
    }

    /**
     * Покупка для оптимистичной стратегии: ничего не записывается, если данные успели измениться.
     * При проигрыше возвращаем {@link Outcome#casConflict} — стратегия повторит транзакцию заново.
     */
    public static Outcome buyCas(Book book, String lotId, String buyerId, int qty, long expectedVersion) {
        AtomicReference<LotV> ref = book.lotRef(lotId);
        if (ref == null) {
            return Outcome.rejected(Call.Reason.NOT_FOUND, 404, null);
        }
        LotV lot = ref.get();
        Hazard.at(Hazard.A_READ);
        if (lot == null) {
            return Outcome.rejected(Call.Reason.NOT_FOUND, 404, null);
        }
        if (!lot.isOpen()) {
            return Outcome.rejected(soldOutOrCancelled(lot), 409, lot);
        }
        if (expectedVersion >= 0 && expectedVersion != lot.version()) {
            return Outcome.rejected(Call.Reason.CONFLICT, 409, lot);
        }
        long cost = lot.price() * qty;
        AtomicLong buyerWallet = book.buyerWallet(buyerId);
        long balance = buyerWallet.get();
        if (balance < cost) {
            return Outcome.rejected(Call.Reason.INSUFFICIENT_FUNDS, 409, lot);
        }
        if (lot.stock() < qty) {
            return Outcome.rejected(Call.Reason.OUT_OF_STOCK, 409, lot);
        }

        // 1) условно записываем лот
        LotV next = lot.afterBuy(qty);
        Hazard.at(Hazard.B_CHECK_THEN_ACT);
        if (!ref.compareAndSet(lot, next)) {
            return Outcome.retryNeeded();               // лот успел измениться — начнём сначала
        }

        // 2) условно списываем деньги: иначе два платежа с одного прочитанного баланса
        Hazard.at(Hazard.C_SPLIT_TRANSFER);
        if (!buyerWallet.compareAndSet(balance, balance - cost)) {
            ref.compareAndSet(next, lot);               // откат (compensating transaction), повтор не нужен
            return Outcome.retryNeeded();               // но баланс пересчитаем с нуля
        }

        // 3) зачисление продавцу коммутативно — крутим CAS на месте, конфликта остатков не будет
        AtomicLong sellerWallet = book.sellerWallet(lot.sellerId());
        long earned;
        do {
            earned = sellerWallet.get();
        } while (!sellerWallet.compareAndSet(earned, earned + cost));

        book.recordAckedWrite(lotId);
        book.recordAckedUnits(lotId, qty);
        return Outcome.applied(next);
    }

    // ------------------------------------ цена и отзыв --------------------------------

    /**
     * Смена цены продавцом. Ровно те же риски, что и у покупки: продавец читает цену,
     * «делает уценку от увиденного» и пишет результат — параллельные покупки затираются им.
     */
    public static Outcome setPrice(Book book, String lotId, long newPrice, long expectedVersion) {
        AtomicReference<LotV> ref = book.lotRef(lotId);
        if (ref == null || ref.get() == null) {
            return Outcome.rejected(Call.Reason.NOT_FOUND, 404, null);
        }
        LotV lot = ref.get();
        if (expectedVersion >= 0 && expectedVersion != lot.version()) {
            return Outcome.rejected(Call.Reason.CONFLICT, 409, lot);
        }
        Hazard.at(Hazard.A_READ);                       // РИСК: пока продавец «думал», лот уже купили
        LotV next = lot.price(newPrice);
        Hazard.at(Hazard.B_CHECK_THEN_ACT);
        ref.set(next);                                  // РИСК: версия взята из устаревшего чтения
        book.recordAckedWrite(lotId);
        return Outcome.applied(next);
    }

    /** Условная (CAS) смена цены — для оптимистичной стратегии. */
    public static Outcome setPriceCas(Book book, String lotId, long newPrice) {
        AtomicReference<LotV> ref = book.lotRef(lotId);
        if (ref == null || ref.get() == null) {
            return Outcome.rejected(Call.Reason.NOT_FOUND, 404, null);
        }
        LotV lot = ref.get();
        Hazard.at(Hazard.A_READ);
        LotV next = lot.price(newPrice);
        Hazard.at(Hazard.B_CHECK_THEN_ACT);
        if (!ref.compareAndSet(lot, next)) {
            return Outcome.retryNeeded();
        }
        book.recordAckedWrite(lotId);
        return Outcome.applied(next);
    }

    /** Отзыв лота продавцом: конфликт с покупками — отозвали в момент, когда лот допродавали. */
    public static Outcome cancel(Book book, String lotId) {
        AtomicReference<LotV> ref = book.lotRef(lotId);
        if (ref == null || ref.get() == null) {
            return Outcome.rejected(Call.Reason.NOT_FOUND, 404, null);
        }
        LotV lot = ref.get();
        Hazard.at(Hazard.A_READ);
        LotV next = lot.cancelled();
        ref.set(next);                                  // РИСК: затрёт параллельную покупку
        book.recordAckedWrite(lotId);
        return Outcome.applied(next);
    }

    public static Outcome cancelCas(Book book, String lotId) {
        AtomicReference<LotV> ref = book.lotRef(lotId);
        if (ref == null || ref.get() == null) {
            return Outcome.rejected(Call.Reason.NOT_FOUND, 404, null);
        }
        LotV lot = ref.get();
        Hazard.at(Hazard.A_READ);
        LotV next = lot.cancelled();
        if (!ref.compareAndSet(lot, next)) {
            return Outcome.retryNeeded();
        }
        book.recordAckedWrite(lotId);
        return Outcome.applied(next);
    }

    private static String soldOutOrCancelled(LotV lot) {
        return LotView.SOLD_OUT.equals(lot.status()) ? Call.Reason.OUT_OF_STOCK : Call.Reason.CANCELLED;
    }
}
