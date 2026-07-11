package com.simplerag.application.usecase;

public final class StaleTaskException extends IllegalStateException {
    public StaleTaskException(String message) {
        super(message);
    }
}
