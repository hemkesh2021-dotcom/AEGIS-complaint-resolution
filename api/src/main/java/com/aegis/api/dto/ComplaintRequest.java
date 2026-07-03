package com.aegis.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Incoming complaint payload.
 *
 * @param text         the free-text complaint narrative (required)
 * @param customerName display name for the drafted reply (optional)
 * @param email        customer email for notifications (optional; never sent to the LLM)
 * @param complaintId  case identifier (optional; generated if absent)
 * @param receivedAt   ISO-8601 timestamp the complaint was received (optional; defaults to now)
 */
public record ComplaintRequest(
        @NotBlank(message = "complaint text must not be blank")
        @Size(max = 8000, message = "complaint text is too long")
        String text,
        String customerName,
        @Email(message = "invalid email address")
        @Size(max = 254, message = "email is too long")
        String email,
        String complaintId,
        String receivedAt,
        String channel) {
}
