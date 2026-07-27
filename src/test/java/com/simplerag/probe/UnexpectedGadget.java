package com.simplerag.probe;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serial;
import java.io.Serializable;

/**
 * Stand-in for a deserialization gadget: a serializable class that lives outside the index snapshot's
 * object graph and records whether its {@code readObject} was ever invoked.
 *
 * <p>Deliberately placed in {@code com.simplerag.probe} so it is not covered by the snapshot
 * deserialization allowlist. {@link #deserialized} staying false proves the filter rejected the class
 * before any of its code ran — a plain {@code Optional.empty()} result would not prove that.
 */
public final class UnexpectedGadget implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public static volatile boolean deserialized;

    private final String marker;

    public UnexpectedGadget(String marker) {
        this.marker = marker;
    }

    public String marker() {
        return marker;
    }

    public static void reset() {
        deserialized = false;
    }

    @Serial
    private void readObject(ObjectInputStream input) throws IOException, ClassNotFoundException {
        input.defaultReadObject();
        deserialized = true;
    }
}
