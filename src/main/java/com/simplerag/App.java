package com.simplerag;

import com.simplerag.bootstrap.AppCompositionRoot;

public final class App {
    private App() {
    }

    public static void main(String[] args) {
        new AppCompositionRoot().start();
    }
}
