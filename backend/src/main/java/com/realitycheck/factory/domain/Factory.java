package com.realitycheck.factory.domain;

import com.realitycheck.common.domain.BaseEntity;
import com.realitycheck.common.domain.Ids;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "factory")
public class Factory extends BaseEntity {

    @Column(nullable = false, length = 120)
    private String name;

    @Column
    private String description;

    @Column(name = "time_zone", nullable = false, length = 64)
    private String timeZone;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(nullable = false)
    private boolean demo;

    @Version
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "factory", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("dayOfWeek ASC, startTime ASC")
    private List<WorkingShift> shifts = new ArrayList<>();

    protected Factory() {
    }

    public Factory(UUID id, Instant now, String name, String description, String timeZone, String currencyCode,
            boolean demo) {
        super(id, now);
        this.name = name;
        this.description = description;
        this.timeZone = timeZone;
        this.currencyCode = currencyCode;
        this.demo = demo;
        this.updatedAt = now;
    }

    public WorkingShift addShift(DayOfWeek day, LocalTime start, LocalTime end) {
        WorkingShift shift = new WorkingShift(Ids.child(getId(), "shift:" + day.getValue() + ":" + start),
                getCreatedAt(), this, day, start, end);
        shifts.add(shift);
        return shift;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public boolean isDemo() {
        return demo;
    }

    public long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<WorkingShift> getShifts() {
        return Collections.unmodifiableList(shifts);
    }
}
