package ru.lab.concurrent.harness;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import ru.lab.concurrent.core.cli.Args;
import ru.lab.concurrent.server.ExchangeLabApplication;

/**
 * Владелец запущенной биржи. Харнес умеет работать в двух режимах:
 * поднимать сервис в своём JVM (быстро, ничего не надо настраивать) или подключаться
 * к уже запущенной бинаре (тогда студенты видят, что протокол и «клиент» от сервиса не зависят).
 */
public record ServerHolder(ConfigurableApplicationContext context, String base) {

    public static ServerHolder external(String base) {
        return new ServerHolder(null, base.endsWith("/") ? base.substring(0, base.length() - 1) : base);
    }

    public static ServerHolder startOwn(Args a, long hazard) {
        try {
            // аргументы командной строки Spring читает с ВЫСШИМ приоритетом, поэтому именно так
            // перебиваем server.port из application.yml (setDefaultProperties — низший приоритет)
            ConfigurableApplicationContext context = new SpringApplicationBuilder(ExchangeLabApplication.class)
                    .web(WebApplicationType.SERVLET)
                    .properties(
                            "spring.main.banner-mode=off",
                            "logging.level.root=WARN")
                    .run(
                            "--server.port=" + a.str("port", "0"),
                            "--exchange.hazard-micros=" + hazard,
                            "--server.tomcat.threads.max=64");
            if (context instanceof WebServerApplicationContext web && web.getWebServer() != null) {
                return new ServerHolder(context, "http://localhost:" + web.getWebServer().getPort());
            }
            context.close();
            return null;
        } catch (Exception e) {
            System.out.println("не подняли биржу внутри харнеса: " + e.getMessage());
            return null;
        }
    }

    public void close() {
        if (context != null) {
            context.close();
        }
    }
}
