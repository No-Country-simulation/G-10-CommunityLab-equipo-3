package com.nocountry.simulation.communitylab.infrastructure.exception;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.nocountry.simulation.communitylab.application.exception.PackageTooLargeException;
import com.nocountry.simulation.communitylab.application.exception.StorageFailureException;
import com.nocountry.simulation.communitylab.application.exception.StorageMetricsUnavailableException;
import com.nocountry.simulation.communitylab.application.exception.StorageNotConfiguredException;
import com.nocountry.simulation.communitylab.domain.exception.InvalidAssetException;
import com.nocountry.simulation.communitylab.domain.exception.PostNotFoundException;
import com.nocountry.simulation.communitylab.domain.exception.PostNotRevisableException;
import com.nocountry.simulation.communitylab.domain.exception.PostVersionConflictException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    // Only field names and constraint messages: rejected values carry post text (RNF-04).
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleInvalidBody(MethodArgumentNotValidException ex) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .toList();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "invalid revision");
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    // Fixed detail: Jackson's message quotes fragments of the body.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadableBody(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "malformed body"));
    }

    // ---------- revision outcomes (spec 007): one status per cause so the dashboard knows what to do ----------

    // The domain messages are fixed rule texts without body values, so the user can see which rule failed.
    @ExceptionHandler(InvalidAssetException.class)
    public ResponseEntity<ProblemDetail> handleChannelRules(InvalidAssetException ex) {
        return problem(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(PostNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(PostNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "post not found");
    }

    @ExceptionHandler(PostVersionConflictException.class)
    public ResponseEntity<ProblemDetail> handleConflict(PostVersionConflictException ex) {
        return problem(HttpStatus.CONFLICT, "post was revised meanwhile, reload it and retry");
    }

    @ExceptionHandler(PackageTooLargeException.class)
    public ResponseEntity<ProblemDetail> handleTooLarge(PackageTooLargeException ex) {
        return problem(HttpStatus.CONTENT_TOO_LARGE, "package over the 1MB budget");
    }

    @ExceptionHandler(PostNotRevisableException.class)
    public ResponseEntity<ProblemDetail> handleNotRevisable(PostNotRevisableException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "this record has no post to revise");
    }

    // Fixed detail: an OCI message may carry request ids or URLs (RF-09: no provider details).
    @ExceptionHandler(StorageFailureException.class)
    public ResponseEntity<ProblemDetail> handleStorageFailure(StorageFailureException ex) {
        return problem(HttpStatus.BAD_GATEWAY, "object storage unavailable");
    }

    @ExceptionHandler(StorageNotConfiguredException.class)
    public ResponseEntity<ProblemDetail> handleNotConfigured(StorageNotConfiguredException ex) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "OCI_NOT_CONFIGURED");
    }

    @ExceptionHandler(StorageMetricsUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleMetricsUnavailable(StorageMetricsUnavailableException ex) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "storage metrics unavailable");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}
