package ru.lab.concurrent.core.dto;

/**
 * Снимок лота — то, что видит клиент в момент времени T.
 *
 * <p>Заметь: это <b>immutable</b> запись. Клиент, который прочитал лот, держит у себя
 * уже устаревшую копию мира. Именно на этом «устаревшем чтении» и строятся все баги лабы:
 * продавец выставляет цену исходя из прочитанной ранее версии, покупатель списывает остаток
 * исходя из прочитанного ранее количества.</p>
 *
 * @param id      идентификатор лота
 * @param sellerId кто выставил (привязка к кошельку продавца)
 * @param title   название товара
 * @param price   цена за единицу
 * @param stock   сколько единиц ещё можно купить
 * @param sold    сколько уже продано
 * @param version счётчик изменений: нужен для оптимистичной блокировки (If-Match)
 * @param status  OPEN / SOLD_OUT / CANCELLED
 */
public record LotView(
        String id,
        String sellerId,
        String title,
        long price,
        int stock,
        int sold,
        long version,
        String status) {

    public static final String OPEN = "OPEN";
    public static final String SOLD_OUT = "SOLD_OUT";
    public static final String CANCELLED = "CANCELLED";

    public boolean isOpen() {
        return OPEN.equals(status);
    }
}
