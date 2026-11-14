package com.realitycheck.factory.domain;

import com.realitycheck.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

/** A non-working period inside a shift. */
@Entity
@Table(name = "calendar_break")
public class CalendarBreak extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "working_shift_id", nullable = false)
    private WorkingShift shift;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    protected CalendarBreak() {
    }

    CalendarBreak(UUID id, Instant now, WorkingShift shift, LocalTime startTime, LocalTime endTime) {
        super(id, now);
        this.shift = shift;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }
}
