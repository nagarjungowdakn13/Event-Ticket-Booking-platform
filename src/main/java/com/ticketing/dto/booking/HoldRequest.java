package com.ticketing.dto.booking;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * Request to place a temporary hold on specific seats of an event.
 *
 * @param eventId the event the seats belong to.
 * @param seatIds the seat ids to hold (deduplicated by the service). Must be
 *                non-empty and every element must be a positive, non-null id.
 */
public record HoldRequest(

        @NotNull(message = "eventId is required")
        @Positive(message = "eventId must be positive")
        Long eventId,

        @NotEmpty(message = "seatIds must contain at least one seat")
        List<@NotNull(message = "seatId must not be null") @Positive(message = "seatId must be positive") Long> seatIds
) {
}
