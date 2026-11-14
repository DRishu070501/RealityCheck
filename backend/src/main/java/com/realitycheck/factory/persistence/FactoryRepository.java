package com.realitycheck.factory.persistence;

import com.realitycheck.factory.domain.Factory;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FactoryRepository extends JpaRepository<Factory, UUID> {

    boolean existsByNameIgnoreCase(String name);
}
