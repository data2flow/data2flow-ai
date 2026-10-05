package net.java21.data2flow.ai.llm;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 제공자 등록 목록. 모든 {@link LlmProvider} 값에 구현이 하나씩 있다(없으면 "준비 중") */
public class ProviderRegistry {

    private final Map<LlmProvider, ChatModelProvider> providers = new EnumMap<>(LlmProvider.class);

    public ProviderRegistry(List<ChatModelProvider> registered) {
        for (LlmProvider p : LlmProvider.values()) {
            providers.put(p, p == LlmProvider.NONE ? new NoneProvider() : new PendingProvider(p));
        }
        registered.forEach(p -> providers.put(p.provider(), p));
    }

    public ChatModelProvider get(LlmProvider provider) {
        return providers.get(provider);
    }
}
