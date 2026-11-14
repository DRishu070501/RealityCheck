package com.realitycheck.factory.domain;

import com.realitycheck.common.domain.BaseEntity;
import com.realitycheck.common.domain.Ids;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** One working period on one weekday, in the factory's local time. */
@Entity
@Table(name = "working_shift")
public class WorkingShift extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "factory_id", nullable = false)
    private Factory factory;

    /** ISO day number: 1 = Monday .. 7 = Sunday. */
    @Column(name = "day_of_week", nullable = false)
    private int dayOfWeek;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @OneToMany(mappedBy = "shift", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("startTime ASC")
    private List<CalendarBreak> breaks = new ArrayList<>();

    protected WorkingShift() {
    }

    WorkingShift(UUID id, Instant now, Factory factory, DayOfWeek day, LocalTime startTime, LocalTime endTime) {
        super(id, now);
        this.factory = factory;
        this.dayOfWeek = day.getValue();
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public void addBreak(LocalTime start, LocalTime end) {
        breaks.add(new CalendarBreak(Ids.child(getId(), "break:" + start), getCreatedAt(), this, start, end));
    }

    public DayOfWeek getDay() {
        return DayOfWeek.of(dayOfWeek);
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public List<CalendarBreak> getBreaks() {
        return Collections.unmodifiableList(breaks);
    }
}
