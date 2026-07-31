package io.lolyay.gma4j.net.client.cert;

import io.lolyay.gma4j.net.codec.encryption.client.IClientKnownCertificateKeeper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FileCertificateKeeper implements IClientKnownCertificateKeeper {

    private static final String STORE_FILE = "known_certs";

    private final Path storeFile;
    private final Map<String, byte[]> knownHashes = new ConcurrentHashMap<>();

    public FileCertificateKeeper(String base) {
        this(Path.of(base));
    }

    public FileCertificateKeeper(Path base) {
        this.storeFile = base.resolve(STORE_FILE);
        load();
    }

    @Override
    public byte[] getKnownCertificateHashForUri(String uri) {
        byte[] hash = knownHashes.get(uri);
        return hash != null ? hash.clone() : null;
    }

    @Override
    public boolean hasCertificateChangedForUri(String uri, byte[] serverProvidedCertificate) {
        byte[] hash = sha256(serverProvidedCertificate);
        byte[] known = knownHashes.putIfAbsent(uri, hash);
        if (known == null) {
            persist();
            return false;
        }
        return !MessageDigest.isEqual(known, hash);
    }

    public void trust(String uri, byte[] certificate) {
        knownHashes.put(uri, sha256(certificate));
        persist();
    }

    public void forget(String uri) {
        if (knownHashes.remove(uri) != null) {
            persist();
        }
    }

    private void load() {
        if (!Files.exists(storeFile)) {
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(storeFile);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read certificate store: " + storeFile, e);
        }
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int separator = trimmed.lastIndexOf(' ');
            if (separator <= 0) {
                continue;
            }
            String uri = trimmed.substring(0, separator).trim();
            byte[] hash = HexFormat.of().parseHex(trimmed.substring(separator + 1).trim());
            knownHashes.put(uri, hash);
        }
    }

    private synchronized void persist() {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, byte[]> entry : knownHashes.entrySet()) {
            builder.append(entry.getKey())
                    .append(' ')
                    .append(HexFormat.of().formatHex(entry.getValue()))
                    .append('\n');
        }
        try {
            Path parent = storeFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(storeFile, builder.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write certificate store: " + storeFile, e);
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
