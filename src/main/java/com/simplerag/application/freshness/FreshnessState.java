package com.simplerag.application.freshness;

public enum FreshnessState {
    STOPPED,
    VERIFYING,
    VERIFIED,
    CHANGED,
    UNAVAILABLE
}
