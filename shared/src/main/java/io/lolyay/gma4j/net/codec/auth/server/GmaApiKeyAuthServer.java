package io.lolyay.gma4j.net.codec.auth.server;

import io.lolyay.gma4j.net.codec.auth.GmaAuthType;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

public class GmaApiKeyAuthServer implements GmaAuthServer {
    private final String apiKey;

    public GmaApiKeyAuthServer(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public GmaAuthType authType() {
        return GmaAuthType.API_KEY;
    }

    @Override
    public byte[] createChallenge(UUID clientId, String claimedClientId, byte[] clientExtraData) {
        return new byte[0];
    }

    @Override
    public boolean verifyClientResponse(byte[] serverChallenge, byte[] response, UUID clientId, String claimedClientId, byte[] stateHash) {
        try {
            String resp = new String(Base64.getDecoder().decode(new String(response, StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
            String[] parts = resp.split(":");
            if (parts.length != 2) return false;
            return parts[0].equals(clientId.toString()) && parts[1].equals(apiKey);
        } catch (Exception e) {
            return false;
        }
    }
}
