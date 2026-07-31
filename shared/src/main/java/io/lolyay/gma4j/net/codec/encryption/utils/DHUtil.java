package io.lolyay.gma4j.net.codec.encryption.utils;

import lombok.experimental.UtilityClass;

import javax.crypto.KeyAgreement;
import java.math.BigInteger;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;

@UtilityClass
public class DHUtil {

    private static final String KEY_ALGORITHM = "EC";
    private static final String CURVE = "secp256r1";
    private static final String AGREEMENT_ALGORITHM = "ECDH";
    private static final int FIELD_SIZE = 32;

    public static final int PUBLIC_KEY_LENGTH = 1 + FIELD_SIZE * 2;

    private static final ECParameterSpec EC_PARAMS = resolveParams();

    private static ECParameterSpec resolveParams() {
        try {
            AlgorithmParameters params = AlgorithmParameters.getInstance(KEY_ALGORITHM);
            params.init(new ECGenParameterSpec(CURVE));
            return params.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public DhState createDhState() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(KEY_ALGORITHM);
            kpg.initialize(new ECGenParameterSpec(CURVE));
            KeyPair keyPair = kpg.generateKeyPair();

            return new DhState(
                    encodePoint(((ECPublicKey) keyPair.getPublic()).getW()),
                    keyPair.getPrivate()
            );
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to generate DH key pair", e);
        }
    }

    public byte[] getSharedSecret(DhState dhState, byte[] remotePublicKey) {
        try {
            ECPublicKey remoteKey = decodePoint(remotePublicKey);

            KeyAgreement keyAgreement = KeyAgreement.getInstance(AGREEMENT_ALGORITHM);
            keyAgreement.init(dhState.privateKey());
            keyAgreement.doPhase(remoteKey, true);

            return keyAgreement.generateSecret();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to compute shared secret", e);
        }
    }

    private byte[] encodePoint(ECPoint point) {
        byte[] out = new byte[PUBLIC_KEY_LENGTH];
        out[0] = 0x04;
        writeFixed(point.getAffineX(), out, 1);
        writeFixed(point.getAffineY(), out, 1 + FIELD_SIZE);
        return out;
    }

    private ECPublicKey decodePoint(byte[] encoded) throws GeneralSecurityException {
        if (encoded.length != PUBLIC_KEY_LENGTH || encoded[0] != 0x04) {
            throw new InvalidKeyException("Expected " + PUBLIC_KEY_LENGTH + "-byte uncompressed EC point");
        }

        byte[] x = new byte[FIELD_SIZE];
        byte[] y = new byte[FIELD_SIZE];
        System.arraycopy(encoded, 1, x, 0, FIELD_SIZE);
        System.arraycopy(encoded, 1 + FIELD_SIZE, y, 0, FIELD_SIZE);

        ECPoint point = new ECPoint(new BigInteger(1, x), new BigInteger(1, y));
        validateOnCurve(point);

        KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM);
        return (ECPublicKey) keyFactory.generatePublic(new ECPublicKeySpec(point, EC_PARAMS));
    }

    private void validateOnCurve(ECPoint point) throws InvalidKeyException {
        if (ECPoint.POINT_INFINITY.equals(point)) {
            throw new InvalidKeyException("EC point is at infinity");
        }

        EllipticCurve curve = EC_PARAMS.getCurve();
        BigInteger p = ((ECFieldFp) curve.getField()).getP();
        BigInteger x = point.getAffineX();
        BigInteger y = point.getAffineY();

        if (x.signum() < 0 || x.compareTo(p) >= 0 || y.signum() < 0 || y.compareTo(p) >= 0) {
            throw new InvalidKeyException("EC point coordinates out of field range");
        }

        BigInteger lhs = y.multiply(y).mod(p);
        BigInteger rhs = x.multiply(x).add(curve.getA()).multiply(x).add(curve.getB()).mod(p);
        if (!lhs.equals(rhs)) {
            throw new InvalidKeyException("EC point is not on the curve");
        }
    }

    private void writeFixed(BigInteger value, byte[] dest, int offset) {
        byte[] raw = value.toByteArray();
        if (raw.length == FIELD_SIZE) {
            System.arraycopy(raw, 0, dest, offset, FIELD_SIZE);
            return;
        }
        if (raw.length == FIELD_SIZE + 1 && raw[0] == 0) {
            System.arraycopy(raw, 1, dest, offset, FIELD_SIZE);
            return;
        }
        if (raw.length < FIELD_SIZE) {
            System.arraycopy(raw, 0, dest, offset + (FIELD_SIZE - raw.length), raw.length);
            return;
        }
        throw new IllegalArgumentException("Coordinate too large for field size");
    }

    public record DhState(byte[] publicKey, PrivateKey privateKey) {}
}
