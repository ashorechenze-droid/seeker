package com.simplerag.application.port.out;

import java.util.Optional;

public interface SettingsRepository {
    Optional<String> getSetting(String key);
    void putSetting(String key, String value);
}
