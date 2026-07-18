package interview.guide.modules.agent.config;

import interview.guide.modules.agent.memory.config.AgentMemoryProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Agent 模块异步配置。
 *
 * <p>启用 Spring 异步方法支持，并注册压缩任务专用线程池，避免使用默认的
 * {@code SimpleAsyncTaskExecutor} 导致无限创建线程。</p>
 */
@Configuration
@EnableAsync
@EnableConfigurationProperties(AgentMemoryProperties.class)
public class AgentAsyncConfig {

    /**
     * 记忆压缩任务专用线程池。
     *
     * <p>参数说明：</p>
     * <ul>
     *   <li>核心线程 2、最大线程 4：压缩是后台任务，不需要高并发，限流防止打爆 LLM RPM</li>
     *   <li>队列 50：兜住突发压缩请求，超出后由 CallerRunsPolicy 在调用线程同步执行</li>
     *   <li>线程名前缀 memory-compress-：便于线程 dump 定位</li>
     * </ul>
     */
    @Bean("memoryCompressExecutor")
    public Executor memoryCompressExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("memory-compress-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
