package com.nordlab.forge;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.BadRequestException;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.Base64;

@ApplicationScoped
public class SecurityService {
    @Inject Database db;
    @ConfigProperty(name="NORD_SESSION_DAYS",defaultValue="14") int sessionDays;
    @ConfigProperty(name="NORD_COOKIE_SECURE",defaultValue="false") boolean cookieSecure;

    private final SecureRandom random=new SecureRandom();

    public SessionCreated register(String email,String password,String displayName){
        email=normEmail(email); displayName=clean(displayName);
        if(displayName.isBlank()) displayName=email.substring(0,email.indexOf('@'));
        validatePassword(password);
        if(db.count("SELECT COUNT(*) FROM users WHERE email=?",email)>0) throw new BadRequestException("email_exists");
        boolean owner=db.count("SELECT COUNT(*) FROM users")==0;
        String forgeKey="FG-"+token(8).substring(0,8).toUpperCase(Locale.ROOT)+"-"+token(8).substring(0,8).toUpperCase(Locale.ROOT);
        long id=db.insert("INSERT INTO users(email,display_name,password_hash,forge_key,platform_owner) VALUES(?,?,?,?,?)",email,displayName,hashPassword(password),forgeKey,owner);
        return createSession(id);
    }

    public SessionCreated login(String email,String password){
        Map<String,Object> row=db.one("SELECT * FROM users WHERE email=?",normEmail(email));
        if(row==null||!"active".equals(row.get("status"))||!verifyPassword(password,String.valueOf(row.get("password_hash")))) throw new NotAuthorizedException("invalid_credentials");
        return createSession(((Number)row.get("id")).longValue());
    }

    public Map<String,Object> requireUser(String token){
        if(token==null||token.isBlank()) throw new NotAuthorizedException("login_required");
        Map<String,Object> row=db.one("""
            SELECT u.id,u.email,u.display_name,u.forge_key,u.platform_owner,u.status,u.created_at,s.csrf_token,s.expires_at
            FROM sessions s JOIN users u ON u.id=s.user_id
            WHERE s.token_hash=? AND s.expires_at>CURRENT_TIMESTAMP AND u.status='active'
            """,sha256(token));
        if(row==null) throw new NotAuthorizedException("session_expired");
        return row;
    }

    public void requireCsrf(String token,String csrf){
        Map<String,Object> u=requireUser(token);
        if(csrf==null||!MessageDigest.isEqual(String.valueOf(u.get("csrf_token")).getBytes(StandardCharsets.UTF_8),csrf.getBytes(StandardCharsets.UTF_8))) throw new ForbiddenException("csrf");
    }

    public void logout(String token){ if(token!=null&&!token.isBlank()) db.execute("DELETE FROM sessions WHERE token_hash=?",sha256(token)); }

    public String cookie(String token){
        StringBuilder s=new StringBuilder("NF_SESSION=").append(token).append("; Path=/; HttpOnly; SameSite=Lax; Max-Age=").append(sessionDays*86400);
        if(cookieSecure) s.append("; Secure");
        return s.toString();
    }
    public String clearCookie(){
        String s="NF_SESSION=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0";
        return cookieSecure?s+"; Secure":s;
    }

    public String randomKey(String prefix){ return prefix+"_"+token(24); }
    public String sha256(String value){
        try{
            byte[] out=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    public String hmac(String secret,String value){
        try{
            Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        }catch(Exception e){throw new IllegalStateException(e);}
    }

    private SessionCreated createSession(long userId){
        String token=token(32), csrf=token(24);
        db.insert("INSERT INTO sessions(user_id,token_hash,csrf_token,expires_at) VALUES(?,?,?,?)",userId,sha256(token),csrf,Timestamp.from(Instant.now().plus(sessionDays,ChronoUnit.DAYS)));
        Map<String,Object> user=requireUser(token);
        return new SessionCreated(token,csrf,user);
    }

    private String hashPassword(String password){
        byte[] salt=new byte[16], out=new byte[32]; random.nextBytes(salt);
        Argon2BytesGenerator gen=new Argon2BytesGenerator();
        gen.init(new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(Argon2Parameters.ARGON2_VERSION_13).withSalt(salt).withIterations(3).withMemoryAsKB(65536).withParallelism(1).build());
        gen.generateBytes(password.toCharArray(),out);
        return "argon2id$3$65536$1$"+Base64.getEncoder().withoutPadding().encodeToString(salt)+"$"+Base64.getEncoder().withoutPadding().encodeToString(out);
    }
    private boolean verifyPassword(String password,String encoded){
        try{
            String[] p=encoded.split("\\$"); if(p.length!=6||!"argon2id".equals(p[0])) return false;
            int it=Integer.parseInt(p[1]), mem=Integer.parseInt(p[2]), par=Integer.parseInt(p[3]);
            byte[] salt=Base64.getDecoder().decode(p[4]), expected=Base64.getDecoder().decode(p[5]), actual=new byte[expected.length];
            Argon2BytesGenerator gen=new Argon2BytesGenerator();
            gen.init(new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(Argon2Parameters.ARGON2_VERSION_13).withSalt(salt).withIterations(it).withMemoryAsKB(mem).withParallelism(par).build());
            gen.generateBytes(password.toCharArray(),actual);
            return MessageDigest.isEqual(expected,actual);
        }catch(Exception e){return false;}
    }
    private String token(int bytes){ byte[] b=new byte[bytes]; random.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    private String normEmail(String s){
        String v=clean(s).toLowerCase(Locale.ROOT);
        if(!v.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw new BadRequestException("invalid_email");
        return v;
    }
    private void validatePassword(String p){ if(p==null||p.length()<10) throw new BadRequestException("password_too_short"); }
    private String clean(String s){return s==null?"":s.trim();}
    public record SessionCreated(String token,String csrf,Map<String,Object> user){}
}
