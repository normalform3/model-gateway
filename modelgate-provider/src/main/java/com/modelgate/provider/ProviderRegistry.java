package com.modelgate.provider;

import com.modelgate.common.error.ErrorCode;
import com.modelgate.common.error.ModelGateException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ProviderRegistry {
    private final Map<String, AiProvider> providers;

    public ProviderRegistry(List<AiProvider> providers) {
        this.providers = providers.stream()
                .collect(Collectors.toUnmodifiableMap(AiProvider::providerName, Function.identity()));
    }

    public AiProvider get(String provider) {
        AiProvider aiProvider = providers.get(provider);
        if (aiProvider == null) {
            throw new ModelGateException(ErrorCode.MODEL_ROUTE_NOT_FOUND, "Provider is not registered: " + provider);
        }
        return aiProvider;
    }
}
