package com.realitycheck.common.error;

import jakarta.validation.ConstraintViolationException;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns every failure into an RFC 9457 problem document. Client-visible text is written here or
 * comes from {@link ApiException}; exception messages from frameworks and drivers are logged, not
 * returned, because they can carry SQL, class names and other internals.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Value of the {@code code} property on a 409 caused by an out-of-date {@code version}. */
    public static final String STALE_VERSION = "STALE_VERSION";

    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApiException(ApiException ex) {
        if (ex instanceof ValidationException validation) {
            return validationProblem(validation.errors());
        }
        return problem(ex.status(), ex.title(), ex.getMessage());
    }

    /**
     * Safety net for a uniqueness or foreign-key race that slipped past a service-level check.
     * The driver message names tables and constraints, so it is logged and not returned.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT, "Conflict", "The change conflicts with existing data.");
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        List<FieldViolation> errors = ex.getConstraintViolations().stream()
                .map(v -> new FieldViolation(lastNode(v.getPropertyPath().toString()), v.getMessage()))
                .sorted(Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::message))
                .toList();
        return validationProblem(errors);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return validationProblem(List.of(new FieldViolation(ex.getName(), "has an invalid value")));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail handleOptimisticLock(OptimisticLockingFailureException ex) {
        ProblemDetail body = problem(HttpStatus.CONFLICT, "Conflict",
                "The record was changed by someone else. Reload it and try again.");
        // Lets a client offer "reload" without matching on the wording of the message.
        body.setProperty("code", STALE_VERSION);
        return body;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error",
                "Something went wrong on the server. The error has been logged.");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new FieldViolation(e.getField(),
                        e.getDefaultMessage() == null ? "is invalid" : e.getDefaultMessage()))
                .sorted(Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::message))
                .toList();
        return handleExceptionInternal(ex, validationProblem(errors), headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "Malformed request",
                "The request body is missing or is not valid JSON for this endpoint.");
        return handleExceptionInternal(ex, body, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail body = problem(HttpStatus.NOT_FOUND, "Not found", "No endpoint matches this path.");
        return handleExceptionInternal(ex, body, headers, HttpStatus.NOT_FOUND, request);
    }

    private static ProblemDetail validationProblem(List<FieldViolation> errors) {
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "Validation failed",
                "One or more fields are invalid.");
        body.setProperty("errors", errors);
        return body;
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle(title);
        return body;
    }

    private static String lastNode(String propertyPath) {
        int dot = propertyPath.lastIndexOf('.');
        return dot < 0 ? propertyPath : propertyPath.substring(dot + 1);
    }
}
