package com.aegis.api.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Generic conflict response: never disclose the existing case or tracking token. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ComplaintConflictException extends RuntimeException {
    public ComplaintConflictException(Throwable cause) {
        super("Complaint creation conflicts with an existing reference", cause);
    }
}
