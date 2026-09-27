package com.aisocialgame.config;

import com.aisocialgame.logging.MdcTaskDecorator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableScheduling
public class AsyncExecutionConfig {

    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(3);
        scheduler.setThreadNamePrefix("game-scheduler-");
        scheduler.initialize();
        return scheduler;
    }

    @Bean(name = "aiStreamTaskExecutor")
    public TaskExecutor aiStreamTaskExecutor(@Value("${app.ai.stream.core-pool-size:4}") int corePoolSize,
                                             @Value("${app.ai.stream.max-pool-size:16}") int maxPoolSize,
                                             @Value("${app.ai.stream.queue-capacity:64}") int queueCapacity,
                                             MdcTaskDecorator mdcTaskDecorator) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, corePoolSize));
        executor.setMaxPoolSize(Math.max(Math.max(1, corePoolSize), maxPoolSize));
        executor.setQueueCapacity(Math.max(0, queueCapacity));
        executor.setThreadNamePrefix("ai-stream-");
        executor.setTaskDecorator(mdcTaskDecorator);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
