package cn.xinkelink.statistics;

import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** A domain-separated statistical credential; deliberately neither JWT nor an authentication principal. */
public final class VisitorCredentials {
    private final byte[] secret;
    private final Clock clock;
    public record Issued(String credential, Instant expiresAt) {
        @Override public String toString() { return "Issued[credential=[REDACTED], expiresAt="+expiresAt+"]"; }
    }
    public VisitorCredentials(String secretBase64,Clock clock) {
        secret=Base64.getDecoder().decode(secretBase64); if(secret.length<32) throw new IllegalArgumentException("Statistics key too short");
        this.clock=clock;
    }
    public Issued issue() {
        long issued=clock.instant().getEpochSecond(), expires=issued+Duration.ofDays(30).toSeconds();
        String body="sv1."+UUID.randomUUID()+"."+issued+"."+expires;
        return new Issued(body+"."+sign("credential:"+body),Instant.ofEpochSecond(expires));
    }
    public String anonymous(String credential) {
        try {
            if(credential==null||credential.length()>512) invalid();
            String[] p=credential.split("\\.",-1);
            if(p.length!=5||!p[0].equals("sv1")||!UUID.fromString(p[1]).toString().equals(p[1])) invalid();
            long issued=Long.parseLong(p[2]),expiry=Long.parseLong(p[3]),now=clock.instant().getEpochSecond();
            String body=String.join(".",Arrays.copyOf(p,4));
            if(issued>now+60||expiry<=now||expiry-issued!=Duration.ofDays(30).toSeconds()
                    || !MessageDigest.isEqual(sign("credential:"+body).getBytes(StandardCharsets.US_ASCII),p[4].getBytes(StandardCharsets.US_ASCII))) invalid();
            return sign("visitor:anonymous:"+p[1]);
        } catch(BusinessException e) { throw e; }
        catch(RuntimeException e) { invalid(); }
        throw new IllegalStateException("Unreachable");
    }
    public String user(long userId) { if(userId<=0) invalid(); return sign("visitor:user:"+userId); }
    public String quota(String address) { return sign("quota:"+address); }
    private String sign(String text) {
        try { var mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret,"HmacSHA256")); return HexFormat.of().formatHex(mac.doFinal(("xinkelink:statistics:v1:"+text).getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.GeneralSecurityException e) { throw new IllegalStateException("Statistics signing unavailable"); }
    }
    private static void invalid() { throw new BusinessException(ErrorCode.VALIDATION_FAILED); }
}
