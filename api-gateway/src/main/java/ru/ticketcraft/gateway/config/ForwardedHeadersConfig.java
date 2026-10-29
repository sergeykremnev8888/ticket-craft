package ru.ticketcraft.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.adapter.ForwardedHeaderTransformer;

/**
 * Удаляет forwarded-заголовки, полученные от внешнего клиента.
 *
 * <p>
 * Gateway принимает подключения напрямую и не доверяет переданным клиентом
 * сведениям об исходном IP, протоколе и адресе сервера.
 */
@Configuration(proxyBeanMethods = false)
public class ForwardedHeadersConfig {

    @Bean
    ForwardedHeaderTransformer forwardedHeaderTransformer() {
        ForwardedHeaderTransformer transformer = new ForwardedHeaderTransformer();
        transformer.setRemoveOnly(true);
        return transformer;
    }
}