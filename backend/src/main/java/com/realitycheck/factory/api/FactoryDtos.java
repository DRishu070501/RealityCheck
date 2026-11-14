package com.realitycheck.factory.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request and response shapes for the factory API. Local times are {@code HH:mm} in the factory's zone. */
public final class FactoryDtos {

    private static final String TIME = "HH:mm";

    private FactoryDtos() {
    }

    public record CreateFactoryRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 2000) String description,
            @NotBlank @Size(max = 64) String timeZone,
            @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a three-letter ISO 4217 code") String currencyCode,
            @NotNull @Valid CalendarRequest calendar) {
    }

    public record CalendarRequest(@NotEmpty @Size(max = 42) List<@NotNull @Valid ShiftRequest> shifts) {
    }

    /** {@code dayOfWeek} is ISO: 1 = Monday .. 7 = Sunday. */
    public record ShiftRequest(
            @NotNull @Min(1) @Max(7) Integer dayOfWeek,
            @NotNull @JsonFormat(pattern = TIME) LocalTime startTime,
            @NotNull @JsonFormat(pattern = TIME) LocalTime endTime,
            @Size(max = 8) List<@NotNull @Valid BreakRequest> breaks) {

        public List<BreakRequest> breaksOrEmpty() {
            return breaks == null ? List.of() : breaks;
        }
    }

    public record BreakRequest(
            @NotNull @JsonFormat(pattern = TIME) LocalTime startTime,
            @NotNull @JsonFormat(pattern = TIME) LocalTime endTime) {
    }

    public record FactorySummaryResponse(UUID id, String name, String timeZone, String currencyCode, boolean demo) {
    }

    /**
     * @param counts how many of each configured thing the factory has, keyed by kind
     *               ({@code workflows}, {@code resources}, ...). A key appears once its module exists.
     */
    public record FactoryResponse(
            UUID id,
            String name,
            String description,
            String timeZone,
            String currencyCode,
            boolean demo,
            CalendarResponse calendar,
            Map<String, Long> counts,
            long version,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** {@code weeklyWorkingSeconds} is shift time minus breaks, summed over the week. */
    public record CalendarResponse(List<ShiftResponse> shifts, long weeklyWorkingSeconds) {
    }

    public record ShiftResponse(
            UUID id,
            int dayOfWeek,
            @JsonFormat(pattern = TIME) LocalTime startTime,
            @JsonFormat(pattern = TIME) LocalTime endTime,
            List<BreakResponse> breaks) {
    }

    public record BreakResponse(
            @JsonFormat(pattern = TIME) LocalTime startTime,
            @JsonFormat(pattern = TIME) LocalTime endTime) {
    }
}
