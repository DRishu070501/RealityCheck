package com.realitycheck.common.error;

/** One entry of the {@code errors} array in a validation problem document. */
public record FieldViolation(String field, String message) {
}
