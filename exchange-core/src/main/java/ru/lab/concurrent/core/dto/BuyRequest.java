package ru.lab.concurrent.core.dto;

/**
 * Тело запроса {@code POST /api/lots/{id}/buy} — покупатель берёт {@code qty} единиц.
 *
 * <p>{@code expectedVersion < 0} означает «пиши поверх, что бы там ни было» — так работают
 * наивные клиенты. {@code expectedVersion >= 0} — условная запись: сервер обязан ответить
 * 409, если версия не совпала. Это то, что клиенту нужно научиться передавать,
 * когда сервер перейдёт на оптимистичную блокировку.</p>
 */
public record BuyRequest(String buyerId, int qty, long expectedVersion) {

    public static final int IGNORE_VERSION = -1;

    public static BuyRequest blind(String buyerId, int qty) {
        return new BuyRequest(buyerId, qty, IGNORE_VERSION);
    }
}
