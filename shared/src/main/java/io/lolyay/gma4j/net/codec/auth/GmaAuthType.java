package io.lolyay.gma4j.net.codec.auth;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum GmaAuthType {
    NONE(Integer.MIN_VALUE), // always lowest


    API_KEY(0),
    HMAC_SHA256(100),
    ECC_ECDSA(200),


    CUSTOM(Integer.MAX_VALUE); // always highest

    private final int priority;
}
