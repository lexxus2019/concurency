package ru.lab.concurrent.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Биржа. Один Spring Boot сервис, в нём — изменяемое общее состояние: лоты и кошельки.
 *
 * <p>Сервис заведомо не однопоточный: Tomcat обслуживает каждый HTTP-запрос
 * своим потоком из пула, поэтому два клиента физически выполняют одну и ту же операцию
 * одновременно. Способ синхронизации выбирается свойством {@code exchange.strategy}
 * и переключается на живом процессе: {@code POST /api/lab/strategy?name=sync}.</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ExchangeLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExchangeLabApplication.class, args);
    }
}
