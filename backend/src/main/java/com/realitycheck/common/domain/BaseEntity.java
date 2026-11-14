package com.realitycheck.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.Hibernate;
import org.springframework.data.domain.Persistable;

/**
 * Identity and creation time for every entity. Ids are assigned by the application, so
 * {@link Persistable} tells Spring Data when a row is new instead of letting it issue a SELECT.
 */
@MappedSuperclass
public abstract class BaseEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean persisted;

    protected BaseEntity() {
    }

    protected BaseEntity(UUID id, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    @Override
    public UUID getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @PostPersist
    @PostLoad
    void markPersisted() {
        persisted = true;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BaseEntity that
                && Hibernate.getClass(this) == Hibernate.getClass(that)
                && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
