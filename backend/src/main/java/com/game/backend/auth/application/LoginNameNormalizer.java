package com.game.backend.auth.application;

import java.text.Normalizer;
import java.util.Locale;

public final class LoginNameNormalizer {
    private LoginNameNormalizer() {
    }

    public static String normalize(String loginName) {
        if (loginName == null) {
            return "";
        }
        return Normalizer.normalize(loginName, Normalizer.Form.NFKC)
            .trim()
            .toLowerCase(Locale.ROOT);
    }
}
