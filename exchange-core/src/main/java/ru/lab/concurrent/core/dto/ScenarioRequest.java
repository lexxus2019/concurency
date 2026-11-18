package ru.lab.concurrent.core.dto;

/**
 * Параметры сценария для {@code POST /api/lab/reset}.
 * Один и тот же сценарий прогоняется на всех стратегиях — иначе сравнение нечестное.
 *
 * @param sellers         сколько продавцов создать (кошельки seller-1..N)
 * @param buyers          сколько покупателей создать (кошельки buyer-1..N)
 * @param lotsPerSeller   сколько лотов выставляет каждый продавец
 * @param stockPerLot     начальный остаток каждого лота
 * @param pricePerLot     начальная цена каждого лота
 * @param cashPerBuyer    стартовый капитал покупателя
 */
public record ScenarioRequest(
        int sellers,
        int buyers,
        int lotsPerSeller,
        int stockPerLot,
        long pricePerLot,
        long cashPerBuyer) {

    public static ScenarioRequest labDefault() {
        return new ScenarioRequest(3, 8, 4, 50, 100, 100_000);
    }

    public static ScenarioRequest hotLot() {
        // один горячий лот на всех: худший случай для оптимистичной блокировки
        return new ScenarioRequest(1, 8, 1, 400, 100, 200_000);
    }
}
