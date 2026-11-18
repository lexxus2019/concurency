package ru.lab.concurrent.core.dto;

/**
 * Тело запроса {@code PUT /api/lots/{id}/price} — продавец меняет цену.
 * Совсем как {@link BuyRequest}: есть «слепая» запись и есть условная.
 */
public record PriceRequest(long price, long expectedVersion) {

    public static PriceRequest blind(long price) {
        return new PriceRequest(price, BuyRequest.IGNORE_VERSION);
    }
}
