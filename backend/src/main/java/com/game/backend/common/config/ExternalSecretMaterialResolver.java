package com.game.backend.common.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ExternalSecretMaterialResolver {
    private static final String FILE_PREFIX = "file:";

    private ExternalSecretMaterialResolver() {
    }

    public static String resolve(String propertyName, String configuredValue) {
        if (configuredValue == null || !configuredValue.startsWith(FILE_PREFIX)) {
            return configuredValue;
        }

        String pathValue = configuredValue.substring(FILE_PREFIX.length()).trim();
        if (pathValue.isEmpty()) {
            throw new IllegalStateException("External secret material path is empty for " + propertyName);
        }
        try {
            String secretMaterial = Files.readString(Path.of(pathValue), StandardCharsets.UTF_8).trim();
            if (secretMaterial.isEmpty()) {
                throw new IllegalStateException("External secret material is empty for " + propertyName);
            }
            return secretMaterial;
        } catch (IOException | InvalidPathException exception) {
            throw new IllegalStateException("Unable to read external secret material for " + propertyName, exception);
        }
    }
}
