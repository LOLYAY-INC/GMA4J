package io.lolyay.gma4j.net.codec.auth.server;

import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.interfaces.DecodedJWT;
import io.lolyay.gma4j.net.codec.auth.GmaAuthType;
import lombok.RequiredArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RequiredArgsConstructor
public class GmaJWTAuthServer implements GmaAuthServer {
    private final JWTVerifier algo;
    private final boolean requireClientNameAsAudience;


    @Override
    public byte[] createChallenge(UUID clientId, String claimedClientId, byte[] clientExtraData) {
        return new byte[0];
    }

    @Override
    public boolean verifyClientResponse(byte[] serverChallenge, byte[] response, UUID clientId, String claimedClientId, byte[] stateHash) {
        try {
            String jwtString = new String(response, StandardCharsets.UTF_8);
            DecodedJWT jwt = algo.verify(jwtString);

            if (requireClientNameAsAudience) {
                return jwt.getAudience() != null && !jwt.getAudience().isEmpty() && jwt.getAudience().contains(claimedClientId);
            }

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public GmaAuthType authType() {
        return GmaAuthType.JWT;
    }

}
