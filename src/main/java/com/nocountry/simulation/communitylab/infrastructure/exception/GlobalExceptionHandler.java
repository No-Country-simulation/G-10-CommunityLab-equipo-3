package com.nocountry.simulation.communitylab.infrastructure.exception;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
}
