package com.realitycheck.production.api;

import com.realitycheck.production.api.ImportDtos.ImportRequest;
import com.realitycheck.production.api.ImportDtos.ImportResult;
import com.realitycheck.production.service.ImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/imports", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Production")
public class ImportController {

    private final ImportService importService;

    public ImportController(ImportService importService) {
        this.importService = importService;
    }

    /**
     * Answers 200 whether or not the rows were accepted: a rejected import reports the file, row and reason
     * in {@code error} and saves nothing. A malformed request (missing rows, too many) is a 400.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Import orders and packet movements, all or nothing; dryRun checks without saving")
    public ImportResult importRows(@Valid @RequestBody ImportRequest request) {
        return importService.importRows(request);
    }
}
