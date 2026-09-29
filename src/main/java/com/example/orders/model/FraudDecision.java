package com.example.orders.model;

/** Fraud screens every order (L10): low-value orders are auto-approved, high-value ones go to an analyst. */
public enum FraudDecision {
    AUTO_APPROVED,
    NEEDS_REVIEW
}
