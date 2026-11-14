package com.realitycheck.simulation.engine;

import java.util.List;
import java.util.stream.Collectors;

/** The input cannot be simulated. Carries every problem found, not just the first. */
public final class InvalidSimulationInputException extends RuntimeException {

    /**
     * @param code    stable identifier, for example {@code REQUIREMENT_EXCEEDS_CAPACITY}
     * @param subject what the problem is about (a stage code, a pool name, a batch id); may be null
     */
    public record Problem(String code, String message, String subject) {
    }

    private final transient List<Problem> problems;

    public InvalidSimulationInputException(List<Problem> problems) {
        super(problems.stream().map(Problem::message).collect(Collectors.joining("; ")));
        this.problems = List.copyOf(problems);
    }

    public List<Problem> problems() {
        return problems;
    }
}
