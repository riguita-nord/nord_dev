package com.nordlab.forgeadmin;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.NotAuthorizedException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class AdminSessionService {
    @ConfigProperty(name="NORD_ADMIN_SSO_SECRET") String ssoSecret;
    @ConfigProperty(name="NORD_ADMIN_SESSION_MINUTES",defaultValue="60") int minutes;
    @ConfigProperty(name="NORD_COOKIE_SECURE",defaultValue="false") boolean secure;

    private final Map<String,Session> sessions=new ConcurrentHashMap<>();
    private final SecureRandom random=new SecureRandom();

    public Session accept(String signed){
        if(signed==null||!signed.contains(".")) throw new NotAuthorizedException("invalid_sso");
        String[] parts=signed.split("\\.",2); String expected=hmac(parts[0]);
        if(!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),parts[1].getBytes(StandardCharsets.UTF_8))) throw new NotAuthorizedException("invalid_sso");
        String payload=new String(Base64.getUrlDecoder().decode(parts[0]),StandardCharsets.UTF_8);
        String[] p=payload.split(":",3); if(p.length!=3) throw new NotAuthorizedException("invalid_sso");
        long exp=Long.parseLong(p[2]); if(exp<Instant.now().getEpochSecond()) throw new NotAuthorizedException("expired_sso");
        long userId=Long.parseLong(p[0]); String email=new String(Base64.getUrlDecoder().decode(p[1]),StandardCharsets.UTF_8);
        String token=token(); Session s=new Session(token,userId,email,Instant.now().plusSeconds(minutes*60L)); sessions.put(hash(token),s); cleanup(); return s;
    }

    public Session require(String token){
        if(token==null||token.isBlank()) throw new NotAuthorizedException("admin_login_required");
        Session s=sessions.get(hash(token)); if(s==null||s.expiresAt().isBefore(Instant.now())) throw new NotAuthorizedException("admin_session_expired"); return s;
    }
    public void remove(String token){ if(token!=null) sessions.remove(hash(token)); }
    public String cookie(String token){ return "NF_ADMIN_SESSION="+token+"; Path=/; HttpOnly; SameSite=Strict; Max-Age="+(minutes*60)+(secure?"; Secure":""); }
    public String clearCookie(){ return "NF_ADMIN_SESSION=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0"+(secure?"; Secure":""); }

    private String token(){ byte[] b=new byte[32]; random.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    private String hash(String s){ try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);} }
    private String hmac(String value){ try{Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(ssoSecret.getBytes(StandardCharsets.UTF_8),"HmacSHA256")); return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);} }
    private void cleanup(){ Instant now=Instant.now(); sessions.entrySet().removeIf(e->e.getValue().expiresAt().isBefore(now)); }
    public record Session(String token,long userId,String email,Instant expiresAt){}
}
