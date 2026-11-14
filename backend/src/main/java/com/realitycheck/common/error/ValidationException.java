package com.realitycheck.common.error;

import java.util.List;
import org.springframework.http.HttpStatus;

/** 400 with field-level detail, for rules that Bean Validation annotations cannot express. */
public class ValidationException extends ApiException {

    private final List<FieldViolation> errors;

    public ValidationException(List<FieldViolation> errors) {
        super(HttpStatus.BAD_REQUEST, "Validation failed", "One or more fields are invalid.");
        this.errors = List.copyOf(errors);
    }

    public ValidationException(String field, String message) {
        this(List.of(new FieldViolation(field, message)));
    }

    public List<FieldViolation> errors() {
        return errors;
    }
}
