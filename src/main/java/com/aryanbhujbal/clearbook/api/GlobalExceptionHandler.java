package com.aryanbhujbal.clearbook.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.aryanbhujbal.clearbook.api.dto.ErrorResponse;
import com.aryanbhujbal.clearbook.domain.Posting.UnbalancedPostingException;
import com.aryanbhujbal.clearbook.ledger.LedgerService.PostingContentionException;
import com.aryanbhujbal.clearbook.ledger.MarginBreachException;
import com.aryanbhujbal.clearbook.ledger.UnknownAccountException;

/**
 * One place where every failure becomes a response.
 *
 * <p>Without this, each controller grows its own try/catch and the API ends up
 * returning four different error shapes for the same class of problem. Worse,
 * anything nobody thought to catch leaks a stack trace — which tells an
 * attacker the framework, the version and the package layout, and tells the
 * caller nothing they can act on.
 *
 * <p>The status codes are chosen on what the caller should <em>do</em>:
 *
 * <ul>
 *   <li><b>400</b> — the request was malformed. Fix it and resend.</li>
 *   <li><b>409</b> — the request was well-formed but the ledger refuses it:
 *   margin would breach. Resending will not help; the position has to
 *   change.</li>
 *   <li><b>422</b> — the account named does not exist. Open it first.</li>
 *   <li><b>503</b> — contention, not an error of the caller's. The trade may
 *   still be valid; retrying later is a reasonable thing to do, which is
 *   exactly what a 5xx signals and a 4xx does not.</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Bean validation on a request body. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> onValidationFailure(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.validation(fieldErrors));
    }

    /**
     * A domain invariant rejected the input — a negative quantity, a symbol
     * that is not a venue code, a sub-paise price.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> onIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("INVALID_REQUEST", ex.getMessage()));
    }

    @ExceptionHandler(MarginBreachException.class)
    public ResponseEntity<ErrorResponse> onMarginBreach(MarginBreachException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("MARGIN_BREACH", ex.getMessage())
                        .withDetails(List.of(
                                "account " + ex.getAccountCode(),
                                "equity " + ex.getSnapshot().equity().toPlainString(),
                                "requirement " + ex.getSnapshot().requirement().toPlainString())));
    }

    @ExceptionHandler(UnknownAccountException.class)
    public ResponseEntity<ErrorResponse> onUnknownAccount(UnknownAccountException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ErrorResponse.of("UNKNOWN_ACCOUNT", ex.getMessage()));
    }

    @ExceptionHandler(PostingContentionException.class)
    public ResponseEntity<ErrorResponse> onContention(PostingContentionException ex) {
        log.warn("gave up on a posting after exhausting its retry budget", ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ErrorResponse.of("CONTENTION",
                        "the account is under heavy concurrent activity; retry shortly"));
    }

    /**
     * An unbalanced posting is a bug in this service, never bad input — so it
     * is a 500, and it is logged loudly. Reaching here means something
     * constructed lines that do not net to zero, and the database's own
     * constraint would have stopped it a moment later anyway.
     */
    @ExceptionHandler(UnbalancedPostingException.class)
    public ResponseEntity<ErrorResponse> onUnbalanced(UnbalancedPostingException ex) {
        log.error("refused to post an unbalanced entry — this is a bug", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("LEDGER_INVARIANT",
                        "the ledger refused an entry that did not balance"));
    }

    /** The catch-all, so nothing ever leaks a stack trace to a caller. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> onAnythingElse(Exception ex) {
        log.error("unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("INTERNAL_ERROR", "the request could not be completed"));
    }
}
