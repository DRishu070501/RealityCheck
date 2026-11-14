package com.realitycheck.common.error;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * A failure whose message is safe to show to an API client. Anything else that escapes a
 * controller is reported as a generic 500.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String title;
    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final Map<String, String> headers = new LinkedHashMap<>();

    protected ApiException(HttpStatus status, String title, String detail) {
        super(detail);
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    /** Extra members of the problem document, such as a machine-readable {@code code}. */
    public Map<String, Object> properties() {
        return Map.copyOf(properties);
    }

    public Map<String, String> headers() {
        return Map.copyOf(headers);
    }

    public ApiException withProperty(String name, Object value) {
        properties.put(name, value);
        return this;
    }

    public ApiException withHeader(String name, String value) {
        headers.put(name, value);
        return this;
    }

    /** 404: the id does not exist (or is not visible to the caller). */
    public static ApiException notFound(String what, Object id) {
        return new ApiException(HttpStatus.NOT_FOUND, "Not found", what + " " + id + " was not found");
    }

    /** 409: the request conflicts with the current state, for example a duplicate name. */
    public static ApiException conflict(String detail) {
        return new ApiException(HttpStatus.CONFLICT, "Conflict", detail);
    }

    /** 400 without field detail, for a request whose parts contradict each other. */
    public static ApiException badRequest(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "Bad request", detail);
    }

    /** 422: well-formed, but not allowed by a business rule or state transition. */
    public static ApiException unprocessable(String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Request cannot be processed", detail);
    }

    /** 429: the caller should retry later. */
    public static ApiException tooManyRequests(String detail) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests", detail);
    }
}
