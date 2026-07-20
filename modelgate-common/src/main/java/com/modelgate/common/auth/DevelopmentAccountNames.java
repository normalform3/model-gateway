package com.modelgate.common.auth;

import java.util.Locale;

/** Deterministic local-development account names. These values are not for production identities. */
public final class DevelopmentAccountNames {
    public static final String DOMAIN = "modelgate.local";

    private DevelopmentAccountNames() {
    }

    public static String emailFor(String name, int sequence) {
        String base = normalize(name);
        String localPart = sequence <= 1 ? base : base + "-" + sequence;
        return localPart + "@" + DOMAIN;
    }

    public static String normalize(String name) {
        String normalized = name == null ? "" : name.trim().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "-")
                .replaceAll("[^\\p{L}\\p{N}._-]", "-")
                .replaceAll("-+", "-")
                .replaceAll("(^-+|-+$)", "");
        return normalized.isBlank() ? "user" : normalized;
    }
}
