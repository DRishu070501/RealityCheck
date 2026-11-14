package com.realitycheck.simulation.config;

import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Background execution of simulation runs. Permits equal threads plus queue slots, so a submission
 * that got a permit can always be queued: the executor never rejects a task, and a caller who
 * finds no permit gets 429 instead.
 */
@Configuration
@EnableConfigurationProperties(SimulationExecutorConfig.SimulationProperties.class)
public class SimulationExecutorConfig {

    /**
     * @param threads       runs executing at once
     * @param queueCapacity runs waiting for a thread
     */
    @ConfigurationProperties(prefix = "realitycheck.simulation")
    public record SimulationProperties(Integer threads, Integer queueCapacity) {

        public SimulationProperties {
            threads = threads == null ? 2 : threads;
            queueCapacity = queueCapacity == null ? 8 : queueCapacity;
            if (threads < 1 || queueCapacity < 0) {
                throw new IllegalArgumentException("realitycheck.simulation.threads must be at least 1 and"
                        + " queue-capacity zero or more");
            }
        }
    }

    @Bean(destroyMethod = "shutdown")
    @Qualifier("simulationExecutor")
    public ThreadPoolTaskExecutor simulationExecutor(SimulationProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.threads());
        executor.setMaxPoolSize(properties.threads());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("simulation-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    @Bean
    @Qualifier("simulationPermits")
    public Semaphore simulationPermits(SimulationProperties properties) {
        return new Semaphore(properties.threads() + properties.queueCapacity());
    }
}
