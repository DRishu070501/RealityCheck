package com.realitycheck.common.error;

import org.springframework.http.HttpStatus;

/**
 * A failure whose message is safe to show to an API client. Anything else that escapes a
 * controller is reported as a generic 500.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String title;

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

    /** 404: the id does not exist (or is not visible to the caller). */
    public static ApiException notFound(String what, Object id) {
        return new ApiException(HttpStatus.NOT_FOUND, "Not found", what + " " + id + " was not found");
    }

    /** 409: the request conflicts with the current state, for example a duplicate name. */
    public static ApiException conflict(String detail) {
        return new ApiException(HttpStatus.CONFLICT, "Conflict", detail);
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
