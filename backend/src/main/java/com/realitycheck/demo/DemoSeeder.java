package com.realitycheck.demo;

import jakarta.persistence.EntityManager;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates, and can erase and re-create, the demonstration factory. */
@Service
public class DemoSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

    private final List<DemoSeedSection> sections;
    private final EntityManager entityManager;

    public DemoSeeder(List<DemoSeedSection> sections, EntityManager entityManager) {
        this.sections = sections.stream().sorted(Comparator.comparingInt(DemoSeedSection::order)).toList();
        this.entityManager = entityManager;
    }

    /**
     * Runs every section in order. One transaction: if any section fails, nothing is kept.
     *
     * @return the id of the demonstration factory
     */
    @Transactional
    public UUID seed() {
        DemoSeedContext context = new DemoSeedContext();
        for (DemoSeedSection section : sections) {
            section.seed(context);
        }
        log.info("Seeded demonstration factory factoryId={} sections={}", context.factoryId(), sections.size());
        return context.factoryId();
    }

    /**
     * Erases all application data and seeds again, in one transaction. Every table except the
     * migration history is emptied, including append-only ones: TRUNCATE does not fire their
     * row-level guard, and this is the only sanctioned way around it.
     */
    @Transactional
    public UUID reset() {
        entityManager.flush();
        entityManager.clear();
        Object tables = entityManager.createNativeQuery("""
                select string_agg(format('%I.%I', table_schema, table_name), ', ')
                from information_schema.tables
                where table_schema = current_schema()
                  and table_type = 'BASE TABLE'
                  and table_name <> 'flyway_schema_history'""").getSingleResult();
        if (tables != null) {
            entityManager.createNativeQuery("truncate table " + tables + " restart identity cascade")
                    .executeUpdate();
        }
        log.warn("All application data erased for a demonstration reset");
        return seed();
    }
}
