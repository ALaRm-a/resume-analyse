package interview.guide.modules.agent.config;

import interview.guide.modules.agent.memory.config.AgentMemoryProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Agent 模块异步配置。
 *
 * <p>启用 Spring 异步方法支持，并注册压缩任务专用线程池，避免使用默认的
 * {@code SimpleAsyncTaskExecutor} 导致无限创建线程。</p>
 *
 * <p>同时实现 {@link WebMvcConfigurer} 以接管 Spring MVC 异步请求处理
 * （如 SSE 流式响应）的线程池，替换默认的 SimpleAsyncTaskExecutor。</p>
 */
@Configuration
@EnableAsync
@EnableConfigurationProperties(AgentMemoryProperties.class)
public class AgentAsyncConfig implements WebMvcConfigurer {

    /**
     * Spring MVC 异步请求处理线程池。
     *
     * <p>当 Controller 返回 StreamingResponseBody / SseEmitter / Flux 等异步类型时，
     * Spring MVC 使用此线程池处理异步任务，替代默认的 SimpleAsyncTaskExecutor。</p>
     *
     * <ul>
     *   <li>核心线程 4、最大线程 16：SSE 流式响应是长连接，需要一定的并发能力</li>
     *   <li>队列 100：兜住突发请求，超出后由 CallerRunsPolicy 降级</li>
     *   <li>线程名前缀 mvc-async-：便于线程 dump 定位</li>
     *   <li>keepAliveSeconds 60：空闲线程及时回收，避免资源浪费</li>
     * </ul>
     */
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(100);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("mvc-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();

        configurer.setTaskExecutor(executor);
        // 异步请求超时时间：5 分钟，适配 SSE 长连接场景
        configurer.setDefaultTimeout(5 * 60 * 1000L);
    }

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
