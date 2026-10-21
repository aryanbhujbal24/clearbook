package com.aryanbhujbal.clearbook.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One error shape for every failure the API can produce.
 *
 * <p>A consistent body matters more than it looks: a caller that has to parse
 * three different error shapes depending on which layer failed will parse none
 * of them, and will end up switching on the status code and discarding the
 * detail — which is the detail that would have told them what to fix.
 *
 * <p>{@code code} is a stable, machine-readable token. {@code message} is for
 * a human. {@code fieldErrors} is populated only for validation failures,
 * where which field was wrong is the entire content of the answer.
 */
public record ErrorResponse(
        Instant timestamp,
        String code,
        String message,
        Map<String, String> fieldErrors,
        List<String> details) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(Instant.now(), code, message, Map.of(), List.of());
    }

    public static ErrorResponse validation(Map<String, String> fieldErrors) {
        return new ErrorResponse(
                Instant.now(),
                "VALIDATION_FAILED",
                "the request was not valid",
                fieldErrors,
                List.of());
    }

    public ErrorResponse withDetails(List<String> details) {
        return new ErrorResponse(timestamp, code, message, fieldErrors, details);
    }
}
