package io.lolyay.gma4j.net.codec.auth;

import static io.lolyay.gma4j.net.shared.SharedConfig.ALLOWED_CLOCK_SKEW_MS;

public final class AuthTimestampValidator {
    private AuthTimestampValidator() {
    }

    public static boolean isWithinAllowedSkew(long timestamp, long now) {
        return ALLOWED_CLOCK_SKEW_MS >= 0
                && timestamp >= now - ALLOWED_CLOCK_SKEW_MS
                && timestamp <= now + ALLOWED_CLOCK_SKEW_MS;
    }
}
