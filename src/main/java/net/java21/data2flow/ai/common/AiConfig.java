package net.java21.data2flow.ai.common;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import net.java21.data2flow.ai.commentary.CommentaryRepository;
import net.java21.data2flow.ai.commentary.CommentaryService;
import net.java21.data2flow.ai.commentary.SubjectLoader;
import net.java21.data2flow.ai.conversation.ConversationRepository;
import net.java21.data2flow.ai.conversation.ConversationService;
import net.java21.data2flow.ai.eval.EvalRunRepository;
import net.java21.data2flow.ai.eval.EvalService;
import net.java21.data2flow.ai.events.AutoCommentaryHandler;
import net.java21.data2flow.ai.help.DocsAnswerService;
import net.java21.data2flow.ai.help.HelpChunkRepository;
import net.java21.data2flow.ai.help.HelpIndexer;
import net.java21.data2flow.ai.llm.AnthropicProvider;
import net.java21.data2flow.ai.llm.ChatModelProvider;
import net.java21.data2flow.ai.llm.DefaultLlmGateway;
import net.java21.data2flow.ai.llm.DemoChatModel;
import net.java21.data2flow.ai.llm.FakeProvider;
import net.java21.data2flow.ai.llm.LlmGateway;
import net.java21.data2flow.ai.llm.LlmInvoker;
import net.java21.data2flow.ai.llm.ProviderRegistry;
import net.java21.data2flow.ai.mcp.McpCaller;
import net.java21.data2flow.ai.mcp.McpGuardFilter;
import net.java21.data2flow.ai.mcp.McpToolRegistry;
import net.java21.data2flow.ai.mcp.McpTools;
import net.java21.data2flow.ai.script.ScriptAssistRepository;
import net.java21.data2flow.ai.script.ScriptAssistService;
import net.java21.data2flow.ai.settings.AiSettingsRepository;
import net.java21.data2flow.ai.settings.AiSettingsService;
import net.java21.data2flow.ai.usage.CounterStore;
import net.java21.data2flow.ai.usage.InMemoryCounterStore;
import net.java21.data2flow.ai.usage.RedisCounterStore;
import net.java21.data2flow.ai.usage.UsageLimiter;
import net.java21.data2flow.ai.usage.UsageRepository;
import net.java21.data2flow.ai.usage.UsageService;
import net.java21.data2flow.contracts.audit.AuditRecorder;
import net.java21.data2flow.contracts.authz.CachingPermissionLookup;
import net.java21.data2flow.contracts.authz.PermissionLookup;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.web.ContractsWebAutoConfiguration;
import net.java21.data2flow.contracts.web.ServletErrorWriter;
import org.flywaydb.core.Flyway;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ai 공통 빈: 시계, Flyway 실행 방식, 내부 클라이언트, 권한, LLM 파사드와 제공자, 한도, 기능 서비스, MCP 서버.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiProperties.class)
@EnableScheduling
public class AiConfig {

    /** 운영 코드는 이 시계만 쓴다(ArchUnit NO_SYSTEM_CLOCK). 시험은 바꿔 끼운다 */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** ADR-030: staging과 prod가 DB 하나를 함께 쓰므로 migrate는 staging 배포와 시험에서만. 그 밖은 validate만 */
    @Bean
    FlywayMigrationStrategy flywayMigrationStrategy(AiProperties properties) {
        return (Flyway flyway) -> {
            if ("migrate".equalsIgnoreCase(properties.flywayMode())) {
                flyway.migrate();
            } else {
                flyway.validate();
            }
        };
    }

    /** 가상 스레드 실행기(감사 전송, 평가 실행) */
    @Bean(destroyMethod = "close")
    ExecutorService aiBackgroundExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    // ───────────── 내부 HTTP(ADR-021) ─────────────

    @Bean
    CoreClient coreClient(AiProperties properties, ExecutorService aiBackgroundExecutor) {
        return new CoreClient(new InternalHttp(properties.coreUri(), Duration.ofSeconds(10)), aiBackgroundExecutor);
    }

    @Bean
    AnalyticsClient analyticsClient(AiProperties properties) {
        return new AnalyticsClient(new InternalHttp(properties.analyticsUri(), Duration.ofSeconds(10)));
    }

    @Bean
    PipelineClient pipelineClient(AiProperties properties) {
        return new PipelineClient(new InternalHttp(properties.pipelineUri(), Duration.ofSeconds(10)));
    }

    /** BR-IAM-13: core 원천 판정을 10초 이내로만 캐시 */
    @Bean
    PermissionLookup permissionLookup(CoreClient core, Clock clock) {
        return new CachingPermissionLookup(core::accessGrant, Duration.ofSeconds(10), clock);
    }

    // ───────────── LLM(ADR-040) ─────────────

    @Bean
    ProviderRegistry providerRegistry(AiProperties properties, ObjectProvider<ChatModel> fakeChatModel) {
        List<ChatModelProvider> providers = new ArrayList<>();
        providers.add(new FakeProvider(fakeChatModel.getIfAvailable(DemoChatModel::new)));
        providers.add(new AnthropicProvider(properties.llm().anthropic(), properties.llm().timeout()));
        return new ProviderRegistry(providers);
    }

    @Bean
    LlmInvoker llmInvoker(ProviderRegistry registry, AiProperties properties, Clock clock) {
        return new LlmInvoker(registry, properties.llm().timeout(), clock);
    }

    @Bean
    CounterStore counterStore(ObjectProvider<StringRedisTemplate> redis, Clock clock) {
        InMemoryCounterStore memory = new InMemoryCounterStore(clock);
        StringRedisTemplate template = redis.getIfAvailable();
        return template == null ? memory : new RedisCounterStore(template, memory);
    }

    @Bean
    UsageLimiter usageLimiter(CounterStore counters, AiProperties properties, Clock clock) {
        return new UsageLimiter(counters, clock, properties.zone());
    }

    @Bean
    AiSettingsService aiSettingsService(AiSettingsRepository repository, EvalRunRepository evalRuns, ProviderRegistry providers,
                                        RoleChecker roleChecker, AuditRecorder audit, AiProperties properties, Clock clock) {
        return new AiSettingsService(repository, evalRuns, providers, roleChecker, audit, properties, clock);
    }

    @Bean
    LlmGateway llmGateway(AiSettingsService settings, LlmInvoker invoker, UsageLimiter limiter, UsageRepository usage, AiProperties properties,
                          Clock clock) {
        return new DefaultLlmGateway(settings, invoker, limiter, usage, properties.llm().pricePerMillion(), clock);
    }

    // ───────────── 기능 ─────────────

    @Bean
    SubjectLoader subjectLoader(CoreClient core, AnalyticsClient analytics) {
        return new SubjectLoader(core, analytics);
    }

    @Bean
    CommentaryService commentaryService(CommentaryRepository repository, SubjectLoader loader, LlmGateway gateway, AiSettingsService settings,
                                        RoleChecker roleChecker, Clock clock) {
        return new CommentaryService(repository, loader, gateway, settings, roleChecker, clock);
    }

    @Bean
    AutoCommentaryHandler autoCommentaryHandler(AiSettingsRepository settings, CommentaryRepository commentaries, CommentaryService service,
                                                SubjectLoader loader, LlmGateway gateway) {
        return new AutoCommentaryHandler(settings, commentaries, service, loader, gateway);
    }

    @Bean
    ScriptAssistService scriptAssistService(ScriptAssistRepository repository, LlmGateway gateway, PipelineClient pipeline, CoreClient core,
                                            AiSettingsService settings, RoleChecker roleChecker, Clock clock) {
        return new ScriptAssistService(repository, gateway, pipeline, core, settings, roleChecker, clock);
    }

    @Bean
    HelpIndexer helpIndexer(HelpChunkRepository repository) {
        return new HelpIndexer(repository);
    }

    @Bean
    DocsAnswerService docsAnswerService(HelpChunkRepository chunks, LlmGateway gateway, AiProperties properties) {
        return new DocsAnswerService(chunks, gateway, properties.webBaseUrl());
    }

    @Bean
    ConversationService conversationService(ConversationRepository repository, DocsAnswerService docs, AiSettingsService settings,
                                            RoleChecker roleChecker, Clock clock) {
        return new ConversationService(repository, docs, settings, roleChecker, clock);
    }

    @Bean
    UsageService usageService(UsageRepository repository, UsageLimiter limiter, AiSettingsService settings, RoleChecker roleChecker,
                              AiProperties properties, Clock clock) {
        return new UsageService(repository, limiter, settings, roleChecker, properties.zone(), clock);
    }

    @Bean
    EvalService evalService(EvalRunRepository runs, LlmInvoker invoker, AiSettingsService settings, RoleChecker roleChecker,
                            ExecutorService aiBackgroundExecutor, Clock clock) {
        return new EvalService(runs, invoker, settings, roleChecker, aiBackgroundExecutor, clock);
    }

    @Bean
    AiJobs aiJobs(AiSettingsRepository settings, UsageRepository usage, HelpIndexer helpIndexer, AiProperties properties, Clock clock) {
        return new AiJobs(settings, usage, helpIndexer, clock);
    }

    // ───────────── MCP 서버(AIA-08) ─────────────

    @Bean
    McpToolRegistry mcpToolRegistry(CoreClient core, RoleChecker roleChecker, UsageRepository usage, AiSettingsService settings,
                                    AiProperties properties, Clock clock) {
        return new McpToolRegistry(new McpTools(core, properties.mcp().maxRows()).definitions(), roleChecker, usage, clock,
                org -> settings.effective(org).enabled());
    }

    /** Spring AI MCP 자동 구성이 이 목록을 서버 도구로 등록한다 */
    @Bean
    List<McpStatelessServerFeatures.SyncToolSpecification> mcpToolSpecifications(McpToolRegistry registry) {
        return registry.specifications();
    }

    /** 무상태 Streamable HTTP 전송 + 신원 헤더를 도구로 옮기는 추출기(자동 구성 빈 대신) */
    @Bean
    WebMvcStatelessServerTransport webMvcStatelessServerTransport(JsonMapper jsonMapper, McpServerStreamableHttpProperties properties) {
        return WebMvcStatelessServerTransport.builder()
                .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
                .messageEndpoint(properties.getMcpEndpoint())
                .contextExtractor(McpCaller::extract)
                .build();
    }

    @Bean
    FilterRegistrationBean<McpGuardFilter> mcpGuardFilter(CounterStore counters, ServletErrorWriter errors, AiProperties properties, Clock clock) {
        FilterRegistrationBean<McpGuardFilter> bean = new FilterRegistrationBean<>(
                new McpGuardFilter(counters, errors, properties.mcp().callsPerMinute(), clock));
        bean.setOrder(ContractsWebAutoConfiguration.IDENTITY_FILTER_ORDER + 10);
        bean.addUrlPatterns("/mcp", "/mcp/*");
        return bean;
    }
}
