package com.realitycheck.workflow.domain;

import com.realitycheck.common.domain.BaseEntity;
import com.realitycheck.common.domain.Ids;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One step of a workflow. Units, per .kiro/steering/simulation-rules.md:
 * {@code stonesPerHour} is per processing slot, {@code setupSeconds} is per job, yields are
 * fractions in (0, 1], {@code fixedCostPerJob} is in the factory's currency.
 */
@Entity
@Table(name = "workflow_stage")
public class WorkflowStage extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workflow_id", nullable = false)
    private Workflow workflow;

    @Column(nullable = false, length = 40)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column
    private String description;

    @Column(nullable = false)
    private int sequence;

    @Column(name = "stones_per_hour", nullable = false, precision = 12, scale = 4)
    private BigDecimal stonesPerHour;

    @Column(name = "setup_seconds", nullable = false)
    private long setupSeconds;

    @Column(name = "stone_yield", nullable = false, precision = 7, scale = 6)
    private BigDecimal stoneYield;

    @Column(name = "carat_yield", nullable = false, precision = 7, scale = 6)
    private BigDecimal caratYield;

    @Column(name = "fixed_cost_per_job", nullable = false, precision = 14, scale = 2)
    private BigDecimal fixedCostPerJob;

    /** WORKING, PARKING, WAITING or JOB_WORK (spec 11). */
    @Column(nullable = false, length = 16)
    private String kind = "WORKING";

    /** For every kind but WORKING: ten calendar durations in seconds, ascending; null for WORKING. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "delay_deciles", columnDefinition = "jsonb")
    private List<Long> delayDeciles;

    /** Working stages only, optional: ten calendar durations in seconds a batch stays after processing. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "handling_deciles", columnDefinition = "jsonb")
    private List<Long> handlingDeciles;

    @Version
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "stage", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<StageRequirement> requirements = new ArrayList<>();

    protected WorkflowStage() {
    }

    public WorkflowStage(Workflow workflow, String code, String name, String description, int sequence,
            BigDecimal stonesPerHour, long setupSeconds, BigDecimal stoneYield, BigDecimal caratYield,
            BigDecimal fixedCostPerJob) {
        super(Ids.child(workflow.getId(), "stage:" + code), workflow.getCreatedAt());
        this.workflow = workflow;
        this.code = code;
        this.name = name;
        this.description = description;
        this.sequence = sequence;
        this.stonesPerHour = stonesPerHour;
        this.setupSeconds = setupSeconds;
        this.stoneYield = stoneYield;
        this.caratYield = caratYield;
        this.fixedCostPerJob = fixedCostPerJob;
        this.updatedAt = workflow.getCreatedAt();
    }

    public void addRequirement(UUID resourceId, int unitsPerSlot) {
        requirements.add(new StageRequirement(Ids.child(getId(), "requirement:" + resourceId), getCreatedAt(),
                this, resourceId, unitsPerSlot));
    }

    public Workflow getWorkflow() {
        return workflow;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public int getSequence() {
        return sequence;
    }

    public BigDecimal getStonesPerHour() {
        return stonesPerHour;
    }

    public long getSetupSeconds() {
        return setupSeconds;
    }

    public BigDecimal getStoneYield() {
        return stoneYield;
    }

    public BigDecimal getCaratYield() {
        return caratYield;
    }

    public BigDecimal getFixedCostPerJob() {
        return fixedCostPerJob;
    }

    public List<StageRequirement> getRequirements() {
        return Collections.unmodifiableList(requirements);
    }

    public String getKind() {
        return kind;
    }

    public List<Long> getDelayDeciles() {
        return delayDeciles == null ? null : List.copyOf(delayDeciles);
    }

    public List<Long> getHandlingDeciles() {
        return handlingDeciles == null ? null : List.copyOf(handlingDeciles);
    }

    /**
     * Sets how long a batch stays at this working stage after processing, with its units already released:
     * time the work is done but the packet has not yet moved on (spec 11, task 8).
     *
     * @param deciles ten calendar durations in seconds, ascending, none negative
     */
    public void handleFor(List<Long> deciles) {
        if (!"WORKING".equals(kind)) {
            throw new IllegalStateException("Only a working stage has a handling delay");
        }
        if (deciles == null || deciles.size() != 10 || deciles.stream().anyMatch(d -> d == null || d < 0)
                || !deciles.stream().sorted().toList().equals(deciles)) {
            throw new IllegalArgumentException("A handling delay needs ten ascending, non-negative durations");
        }
        this.handlingDeciles = List.copyOf(deciles);
    }

    /**
     * Makes the stage hold batches for a measured delay instead of working them on capacity.
     *
     * @param holdingKind PARKING, WAITING or JOB_WORK
     * @param deciles     ten calendar durations in seconds, ascending, none negative
     */
    public void holdFor(String holdingKind, List<Long> deciles) {
        if (!List.of("PARKING", "WAITING", "JOB_WORK").contains(holdingKind)) {
            throw new IllegalArgumentException("Not a holding kind: " + holdingKind);
        }
        if (deciles == null || deciles.size() != 10 || deciles.stream().anyMatch(d -> d == null || d < 0)
                || !deciles.stream().sorted().toList().equals(deciles)) {
            throw new IllegalArgumentException("A holding stage needs ten ascending, non-negative durations");
        }
        this.kind = holdingKind;
        this.delayDeciles = List.copyOf(deciles);
    }
}
