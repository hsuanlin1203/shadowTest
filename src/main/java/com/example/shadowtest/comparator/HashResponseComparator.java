package com.example.shadowtest.comparator;

import com.example.shadowtest.model.LogRecord;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Default comparator: signature is the SHA-256 hex of the raw response body. */
@Component
public class HashResponseComparator implements ResponseComparator {

    @Override
    public String product() {
        return DEFAULT_PRODUCT;
    }

    @Override
    public Signature signature(LogRecord record) {
        String body = record.rawBody() == null ? "" : record.rawBody();
        return new Signature(sha256Hex(body));
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
