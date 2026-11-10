package com.localassistant.config;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Las respuestas del chat se transmiten token a token. Un modelo local puede tardar
 * minutos, así que se amplía el timeout asíncrono (el de Tomcat por defecto es 30 s)
 * y se usa un pool propio y acotado para los streams.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Bean
    public ThreadPoolTaskExecutor chatStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("chat-stream-");
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(8);
        executor.setAllowCoreThreadTimeOut(true);
        return executor;
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(chatStreamExecutor());
        configurer.setDefaultTimeout(Duration.ofMinutes(15).toMillis());
    }
}
