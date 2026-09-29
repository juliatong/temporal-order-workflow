package com.example.orders.model;

import java.util.List;

/**
 * What the customer submits at checkout. Amounts are in cents to keep them exact.
 * {@code reviewDeadlineSeconds}: how long an analyst has for a manual review; 0 means the 24-hour default.
 * It is workflow input, so it is recorded in the history and replays the same way.
 */
public record OrderRequest(
        String orderId,
        String customerId,
        List<LineItem> items,
        long totalCents,
        String cardToken,
        String address,
        long reviewDeadlineSeconds) {}
