package com.realitycheck.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.realitycheck.common.config.RealityCheckProperties.Ai;
import org.junit.jupiter.api.Test;

class AiProviderConfigTest {

    @Test
    void theTemplateAnswersWhenNoLanguageModelIsConfigured() {
        for (Ai ai : new Ai[] {Ai.of(null, null), Ai.of("", "key"), Ai.of("none", "key"), Ai.of("template", "key"),
                Ai.of("anthropic", null), Ai.of("anthropic", "  "), Ai.of("someVendor", "key")}) {
            AiProviderConfig.AiProviders providers = AiProviderConfig.select(ai);
            assertThat(providers.languageModel()).as(ai.toString()).isNull();
            assertThat(providers.active().name()).isEqualTo("template");
        }
    }

    @Test
    void claudeIsUsedWithAProviderNameAndAKey() {
        AiProviderConfig.AiProviders providers = AiProviderConfig.select(Ai.of(" Anthropic ", "key"));

        assertThat(providers.active()).isInstanceOf(AnthropicAiProvider.class);
        assertThat(providers.active().languageModel()).isTrue();
        assertThat(providers.template()).isNotNull();
    }

    @Test
    void theKeyNeverAppearsInTheConfigurationsTextForm() {
        assertThat(Ai.of("anthropic", "sk-ant-secret").toString()).doesNotContain("sk-ant-secret").contains("<set>");
    }
}
