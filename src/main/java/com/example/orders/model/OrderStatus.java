package com.example.orders.model;

/**
 * Where the order is. Unlike v1's status projection, this is the workflow's own state,
 * so it cannot lag or be overwritten out of order.
 */
public enum OrderStatus {
    PLACED,
    STOCK_RESERVED,
    PAYMENT_AUTHORIZED,
    AWAITING_REVIEW,
    APPROVED,
    SHIPMENT_BOOKED,
    CAPTURED,
    REVIEW_REJECTED,
    REVIEW_TIMED_OUT,
    SHIPMENT_FAILED,
    PAYMENT_FAILED,
    PAYMENT_DECLINED,
    CANCELLED
}
