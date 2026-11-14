package com.realitycheck.common.error;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void notFoundBecomesA404Problem() throws Exception {
        mvc.perform(get("/test/missing"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Not found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Factory 42 was not found"));
    }

    @Test
    void businessRuleViolationBecomesA422Problem() throws Exception {
        mvc.perform(get("/test/unprocessable"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("A completed stage cannot be started again"));
    }

    @Test
    void optimisticLockFailureBecomesA409WithoutInternals() throws Exception {
        mvc.perform(get("/test/stale"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(content().string(not(containsString("secret_table"))));
    }

    @Test
    void invalidBodyListsEveryFieldInAStableOrder() throws Exception {
        mvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"capacity\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[0].field").value("capacity"))
                .andExpect(jsonPath("$.errors[1].field").value("name"));
    }

    @Test
    void malformedJsonIsReportedWithoutParserDetails() throws Exception {
        mvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Malformed request"))
                .andExpect(content().string(not(containsString("Jackson"))))
                .andExpect(content().string(not(containsString("Unexpected character"))));
    }

    @Test
    void wrongPathVariableTypeIsAValidationError() throws Exception {
        mvc.perform(get("/test/by-id/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("id"));
    }

    @Test
    void unexpectedFailureHidesTheCause() throws Exception {
        mvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Internal server error"))
                .andExpect(content().string(not(containsString("password=hunter2"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    record SampleRequest(@NotBlank String name, @Min(0) int capacity) {
    }

    @RestController
    static class FailingController {

        @GetMapping("/test/missing")
        String missing() {
            throw ApiException.notFound("Factory", 42);
        }

        @GetMapping("/test/unprocessable")
        String unprocessable() {
            throw ApiException.unprocessable("A completed stage cannot be started again");
        }

        @GetMapping("/test/stale")
        String stale() {
            throw new OptimisticLockingFailureException("Row in secret_table was updated by another transaction");
        }

        @GetMapping("/test/boom")
        String boom() {
            throw new IllegalStateException("connection failed: password=hunter2");
        }

        @GetMapping("/test/by-id/{id}")
        String byId(@PathVariable UUID id) {
            return id.toString();
        }

        @PostMapping("/test/validate")
        String validate(@Valid @RequestBody SampleRequest request) {
            return request.name();
        }
    }
}
