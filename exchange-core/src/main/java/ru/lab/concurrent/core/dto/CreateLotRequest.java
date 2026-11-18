package ru.lab.concurrent.core.dto;

/** Тело запроса {@code POST /api/lots} — продавец выставляет лот. */
public record CreateLotRequest(String sellerId, String title, long price, int stock) {
}
