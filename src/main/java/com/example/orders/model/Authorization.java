package com.example.orders.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * The gateway's answer to an authorization. Approved: a card hold that lapses on its own at {@code holdExpiresAt}.
 * Declined: a business answer, not an error — {@code declineReason} says why, and no hold exists.
 */
public record Authorization(String authId, String holdExpiresAt, String declineReason) {

    public static Authorization declined(String reason) {
        return new Authorization(null, null, reason);
    }

    // Derived, not data: without @JsonIgnore, Jackson would write it as a "declined" field that the record can't read back.
    @JsonIgnore
    public boolean isDeclined() {
        return declineReason != null;
    }
}
