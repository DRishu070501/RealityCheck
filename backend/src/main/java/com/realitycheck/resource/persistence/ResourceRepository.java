package com.realitycheck.resource.persistence;

import com.realitycheck.resource.domain.Resource;
import com.realitycheck.resource.domain.ResourceKind;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResourceRepository extends JpaRepository<Resource, UUID> {

    Page<Resource> findByFactoryId(UUID factoryId, Pageable pageable);

    Page<Resource> findByFactoryIdAndKind(UUID factoryId, ResourceKind kind, Pageable pageable);

    List<Resource> findByFactoryIdOrderByKindDescNameAscIdAsc(UUID factoryId);

    boolean existsByFactoryIdAndNameIgnoreCase(UUID factoryId, String name);

    boolean existsByFactoryIdAndNameIgnoreCaseAndIdNot(UUID factoryId, String name, UUID id);

    long countByFactoryId(UUID factoryId);
}
