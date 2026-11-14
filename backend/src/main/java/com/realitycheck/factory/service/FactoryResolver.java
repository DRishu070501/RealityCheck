package com.realitycheck.factory.service;

import com.realitycheck.common.error.ApiException;
import com.realitycheck.common.error.ValidationException;
import com.realitycheck.factory.domain.Factory;
import com.realitycheck.factory.persistence.FactoryRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the factory a factory-scoped request refers to. The {@code factoryId} parameter may be
 * omitted while exactly one factory exists, which keeps single-factory deployments simple.
 */
@Component
public class FactoryResolver {

    private final FactoryRepository factories;

    public FactoryResolver(FactoryRepository factories) {
        this.factories = factories;
    }

    /** @return the id to use; 404 if the given id is unknown, 400 if none was given and it is ambiguous */
    @Transactional(readOnly = true)
    public UUID resolve(UUID factoryIdOrNull) {
        if (factoryIdOrNull != null) {
            return require(factoryIdOrNull);
        }
        List<Factory> firstTwo = factories.findAll(PageRequest.of(0, 2)).getContent();
        if (firstTwo.size() != 1) {
            throw new ValidationException("factoryId", "is required");
        }
        return firstTwo.get(0).getId();
    }

    /** @return the same id, after confirming the factory exists */
    @Transactional(readOnly = true)
    public UUID require(UUID factoryId) {
        if (!factories.existsById(factoryId)) {
            throw ApiException.notFound("Factory", factoryId);
        }
        return factoryId;
    }
}
