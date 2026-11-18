package com.realitycheck.simulation.api;

import com.realitycheck.simulation.api.BacktestDtos.BacktestResponse;
import com.realitycheck.simulation.api.BacktestDtos.CreateBacktestRequest;
import com.realitycheck.simulation.api.BacktestDtos.Overall;
import com.realitycheck.simulation.backtest.BacktestComparison.ForkComparison;
import com.realitycheck.simulation.backtest.BacktestService;
import com.realitycheck.simulation.backtest.BacktestService.StoredBacktest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/backtests", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Backtest")
public class BacktestController {

    private final BacktestService backtestService;

    public BacktestController(BacktestService backtestService) {
        this.backtestService = backtestService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Simulate the factory from past dates, changing nothing, and compare with what was recorded")
    public BacktestResponse create(@Valid @RequestBody CreateBacktestRequest request) {
        return toResponse(backtestService.run(request.factoryId(), request.forkAts()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "A stored backtest")
    public BacktestResponse get(@PathVariable UUID id) {
        return toResponse(backtestService.get(id));
    }

    @GetMapping
    @Operation(summary = "The factory's latest backtest, as a list of at most one")
    public List<BacktestResponse> latest(@RequestParam UUID factoryId) {
        return backtestService.latest(factoryId).map(BacktestController::toResponse).stream().toList();
    }

    static BacktestResponse toResponse(StoredBacktest stored) {
        List<ForkComparison> forks = stored.forks();
        Overall overall = new Overall(forks.size(),
                medianAbsolute(forks, fork -> fork.finished().percent()),
                medianAbsolute(forks, fork -> fork.medianLeadSeconds().percent()),
                medianAbsolute(forks, ForkComparison::wipError));
        return new BacktestResponse(stored.backtest().getId(), stored.backtest().getFactoryId(),
                stored.backtest().getAlgorithmVersion(), stored.backtest().getCreatedAt(), overall, forks);
    }

    /** The lower median of the absolute values that exist; null when none does. */
    private static BigDecimal medianAbsolute(List<ForkComparison> forks, Function<ForkComparison, BigDecimal> error) {
        List<BigDecimal> values = new ArrayList<>(forks.stream().map(error).filter(Objects::nonNull)
                .map(BigDecimal::abs).toList());
        if (values.isEmpty()) {
            return null;
        }
        values.sort(null);
        return values.get((values.size() - 1) / 2);
    }
}
