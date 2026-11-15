package com.realitycheck.ai.provider;

/**
 * Something that turns an evidence document into the structured insight JSON. Business logic
 * calls only this interface; a vendor's request format is known only by its implementation.
 */
public interface AiProvider {

    /**
     * @throws AiProviderException when the provider cannot be reached, times out or refuses
     */
    AiResponse complete(AiRequest request);

    /** Stored with each insight, for example {@code template} or {@code anthropic}. */
    String name();

    /** True for a language model, whose output must be validated and may need a retry. */
    boolean languageModel();
}
