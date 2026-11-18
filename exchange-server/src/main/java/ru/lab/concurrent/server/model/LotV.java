package ru.lab.concurrent.server.model;

import ru.lab.concurrent.core.dto.LotView;

/**
 * Состояние лота. <b>Immutable</b> — это принципиально: любое изменение порождает новый объект,
 * поэтому «оптимистичная» стратегия может делать CAS (сравнил ссылку — поставил), а «наивная»
 * стратегия может записать поверх устаревшую копию и потерять чужую запись.
 *
 * @param version monotонно растёт на каждое применённое изменение; по нему обнаруживаем lost update
 */
public record LotV(String id,
                   String sellerId,
                   String title,
                   long price,
                   int stock,
                   int sold,
                   long version,
                   String status) {

    public boolean isOpen() {
        return LotView.OPEN.equals(status);
    }

    public LotView toView() {
        return new LotView(id, sellerId, title, price, stock, sold, version, status);
    }

    public LotV price(long newPrice) {
        return new LotV(id, sellerId, title, newPrice, stock, sold, version + 1, status);
    }

    /** Применённая покупка: списывает stock, увеличивает sold, пересчитывает статус. */
    public LotV afterBuy(int qty) {
        int newStock = stock - qty;
        String newStatus = newStock <= 0 ? LotView.SOLD_OUT : status;
        return new LotV(id, sellerId, title, price, newStock, sold + qty, version + 1, newStatus);
    }

    public LotV cancelled() {
        return new LotV(id, sellerId, title, price, stock, sold, version + 1, LotView.CANCELLED);
    }

    public static LotV open(String id, String sellerId, String title, long price, int stock) {
        return new LotV(id, sellerId, title, price, stock, 0, 0, LotView.OPEN);
    }
}
