package com.nordlab.forge;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.Base64;

@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ProtectionResource {
    @Inject Database db;
    @Inject SecurityService security;
    @Inject ForgeService forge;
    @Inject ObjectMapper json;

    @ConfigProperty(name="NORD_FORGE_DATA_DIR",defaultValue="/tmp/nord-forge") String dataRoot;
    @ConfigProperty(name="NORD_PROTECTION_SECRET") String protectionSecret;

    private long uid(String session){ return forge.userId(security.requireUser(session)); }
    private void unsafe(String session,String csrf){ security.requireCsrf(session,csrf); }
    private String text(Map<String,Object>b,String k){Object v=b==null?null:b.get(k);return v==null?"":String.valueOf(v).trim();}
    private long num(Map<String,Object>b,String k,long f){try{return Long.parseLong(String.valueOf(b.get(k)));}catch(Exception e){return f;}}
    private boolean bool(Map<String,Object>b,String k,boolean f){Object v=b==null?null:b.get(k);return v==null?f:Boolean.parseBoolean(String.valueOf(v));}

    @GET @Path("/v2/products/{pid}/protection")
    public Response getProtection(@PathParam("pid") long pid,@CookieParam("NF_SESSION") String session){
        Map<String,Object> p=product(pid); forge.requireWorkspace(uid(session),((Number)p.get("workspace_id")).longValue());
        return Response.ok(Map.of("ok",true,"protection",protection(pid))).build();
    }

    @PUT @Path("/v2/products/{pid}/protection")
    public Response putProtection(@PathParam("pid") long pid,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf,Map<String,Object>b){
        unsafe(session,csrf); Map<String,Object> p=product(pid); long wid=((Number)p.get("workspace_id")).longValue(),actor=uid(session); forge.requireWorkspace(actor,wid,"developer","admin");
        String level=text(b,"protection_level").toLowerCase(Locale.ROOT); if(level.isBlank()) level="standard";
        if(!Set.of("standard","protected","streamed").contains(level)) throw new BadRequestException("invalid_protection_level");
        int heartbeat=(int)Math.max(30,Math.min(3600,num(b,"heartbeat_interval_seconds",300)));
        int grace=(int)Math.max(0,Math.min(86400,num(b,"grace_period_seconds",900)));
        int ttl=(int)Math.max(60,Math.min(3600,num(b,"session_ttl_seconds",300)));
        String modules=text(b,"streamed_modules_json"); if(modules.isBlank()) modules="[]";
        if(db.count("SELECT COUNT(*) FROM product_protection WHERE product_id=?",pid)==0)
            db.execute("INSERT INTO product_protection(product_id,protection_level,integrity_validation,streamed_modules_json,heartbeat_enabled,heartbeat_interval_seconds,grace_period_seconds,fingerprint_binding,session_ttl_seconds,watermarking_enabled,minimum_version,downgrade_block) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                pid,level,bool(b,"integrity_validation",true),modules,bool(b,"heartbeat_enabled",true),heartbeat,grace,bool(b,"fingerprint_binding",true),ttl,bool(b,"watermarking_enabled",true),text(b,"minimum_version"),bool(b,"downgrade_block",false));
        else db.execute("UPDATE product_protection SET protection_level=?,integrity_validation=?,streamed_modules_json=?,heartbeat_enabled=?,heartbeat_interval_seconds=?,grace_period_seconds=?,fingerprint_binding=?,session_ttl_seconds=?,watermarking_enabled=?,minimum_version=?,downgrade_block=? WHERE product_id=?",
                level,bool(b,"integrity_validation",true),modules,bool(b,"heartbeat_enabled",true),heartbeat,grace,bool(b,"fingerprint_binding",true),ttl,bool(b,"watermarking_enabled",true),text(b,"minimum_version"),bool(b,"downgrade_block",false),pid);
        forge.audit(wid,actor,"product.protection.updated",String.valueOf(pid),level);
        return Response.ok(Map.of("ok",true,"protection",protection(pid))).build();
    }

    @POST @Path("/v2/products/{pid}/protection/builds")
    public Response createBuild(@PathParam("pid") long pid,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf,Map<String,Object>b){
        unsafe(session,csrf); Map<String,Object> p=product(pid); long wid=((Number)p.get("workspace_id")).longValue(),actor=uid(session); forge.requireWorkspace(actor,wid,"developer","admin");
        long rid=num(b,"release_id",0); Map<String,Object> r=db.one("SELECT id,version,file_name,storage_path,published FROM releases WHERE id=? AND product_id=?",rid,pid); if(r==null) throw new NotFoundException("release_not_found");
        String buildId="NFB-"+security.randomKey("b").replace("b_","").substring(0,24);
        Map<String,Object> cfg=protection(pid); String mode=String.valueOf(cfg.get("protection_level"));
        Map<String,Object> manifest=new LinkedHashMap<>(); manifest.put("build_id",buildId);manifest.put("product_id",pid);manifest.put("product_slug",p.get("slug"));manifest.put("release_id",rid);manifest.put("version",r.get("version"));manifest.put("file_name",r.get("file_name"));manifest.put("mode",mode);manifest.put("created_at",Instant.now().toString());
        String manifestJson=write(manifest), digest=security.sha256(manifestJson), signature=security.hmac(protectionSecret,digest);
        db.insert("INSERT INTO protection_builds(build_id,workspace_id,product_id,release_id,mode,manifest_json,manifest_sha256,manifest_signature,status) VALUES(?,?,?,?,?,?,?,?, 'active')",buildId,wid,pid,rid,mode,manifestJson,digest,signature);
        forge.audit(wid,actor,"protection.build.created",buildId,String.valueOf(r.get("version")));
        return Response.ok(Map.of("ok",true,"build_id",buildId,"mode",mode,"manifest",manifest,"manifest_sha256",digest,"manifest_signature",signature)).build();
    }

    @POST @Path("/v2/products/{pid}/protection/modules")
    public Response registerModule(@PathParam("pid") long pid,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf,Map<String,Object>b){
        unsafe(session,csrf); Map<String,Object> p=product(pid); long wid=((Number)p.get("workspace_id")).longValue(),actor=uid(session); forge.requireWorkspace(actor,wid,"developer","admin");
        String build=text(b,"build_id"),module=text(b,"module_id"),version=text(b,"version"),payload=text(b,"payload");
        if(build.isBlank()||module.isBlank()||module.length()>160||module.contains("/")||module.contains("\\")||version.isBlank()||version.length()>80) throw new BadRequestException("invalid_protection_module");
        if(db.count("SELECT COUNT(*) FROM protection_builds WHERE build_id=? AND product_id=? AND workspace_id=? AND status='active'",build,pid,wid)==0) throw new NotFoundException("protection_build_not_found");
        byte[] bytes; try{bytes=Base64.getDecoder().decode(payload);}catch(Exception e){throw new BadRequestException("invalid_module_payload");}
        if(bytes.length==0||bytes.length>10*1024*1024) throw new BadRequestException("module_too_large");
        String sha=security.sha256(Base64.getEncoder().encodeToString(bytes)), file=security.sha256(module).substring(0,24)+"-"+security.sha256(version).substring(0,12)+".module";
        Path root=Path.of(dataRoot,"storage","protection-modules",build),path=root.resolve(file);
        try{Files.createDirectories(root);Files.write(path,bytes,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);}catch(Exception e){throw new IllegalStateException("module_storage_failed",e);}
        String sig=security.hmac(protectionSecret,build+"|"+module+"|"+version+"|"+sha+"|"+bytes.length);
        Map<String,Object> old=db.one("SELECT id FROM protection_modules WHERE build_id=? AND module_id=? AND version=?",build,module,version);
        if(old==null) db.insert("INSERT INTO protection_modules(build_id,module_id,version,storage_path,sha256,size_bytes,signature,streamed) VALUES(?,?,?,?,?,?,?,TRUE)",build,module,version,path.toString(),sha,bytes.length,sig);
        else db.execute("UPDATE protection_modules SET storage_path=?,sha256=?,size_bytes=?,signature=?,streamed=TRUE,created_at=CURRENT_TIMESTAMP WHERE id=?",path.toString(),sha,bytes.length,sig,old.get("id"));
        forge.audit(wid,actor,"protection.module.saved",build,module+":"+version);
        return Response.ok(Map.of("ok",true,"module_id",module,"version",version,"sha256",sha,"signature",sig)).build();
    }

    @GET @Path("/v2/workspaces/{wid}/protection/installations")
    public Response installations(@PathParam("wid") long wid,@CookieParam("NF_SESSION") String session){
        forge.requireWorkspace(uid(session),wid,"support","developer","admin");
        return Response.ok(db.query("""
          SELECT pi.*,p.name product_name,u.email user_email
          FROM protection_installations pi JOIN products p ON p.id=pi.product_id JOIN licenses l ON l.id=pi.license_id JOIN users u ON u.id=l.user_id
          WHERE pi.workspace_id=? ORDER BY pi.last_seen_at DESC
          """,wid)).build();
    }

    @POST @Path("/v2/protection/installations/{installationId}/revoke")
    public Response revokeInstallation(@PathParam("installationId") String installationId,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf,Map<String,Object>b){
        unsafe(session,csrf); Map<String,Object> item=db.one("SELECT * FROM protection_installations WHERE installation_id=?",installationId); if(item==null) throw new NotFoundException("installation_not_found");
        long wid=((Number)item.get("workspace_id")).longValue(),actor=uid(session); forge.requireWorkspace(actor,wid,"admin");
        String reason=text(b,"reason"); if(reason.isBlank()) reason="revoked_by_workspace";
        db.execute("UPDATE protection_installations SET status='revoked',revoked_at=CURRENT_TIMESTAMP,revocation_reason=? WHERE installation_id=?",reason,installationId);
        db.execute("UPDATE protection_sessions SET status='revoked' WHERE installation_id=?",installationId);
        db.insert("INSERT INTO protection_revocations(workspace_id,target_type,target_id,reason,revoked_by) VALUES(?,'installation',?,?,?)",wid,installationId,reason,actor);
        forge.audit(wid,actor,"protection.installation.revoked",installationId,reason); return Response.ok(Map.of("ok",true)).build();
    }

    @POST @Path("/v2/protection/builds/{buildId}/revoke")
    public Response revokeBuild(@PathParam("buildId") String buildId,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf,Map<String,Object>b){
        unsafe(session,csrf); Map<String,Object> item=db.one("SELECT * FROM protection_builds WHERE build_id=?",buildId); if(item==null) throw new NotFoundException("build_not_found");
        long wid=((Number)item.get("workspace_id")).longValue(),actor=uid(session); forge.requireWorkspace(actor,wid,"developer","admin");
        String reason=text(b,"reason"); if(reason.isBlank()) reason="revoked_by_workspace";
        db.execute("UPDATE protection_builds SET status='revoked',revoked_at=CURRENT_TIMESTAMP,revocation_reason=? WHERE build_id=?",reason,buildId);
        db.execute("UPDATE protection_sessions SET status='revoked' WHERE build_id=?",buildId);
        db.insert("INSERT INTO protection_revocations(workspace_id,target_type,target_id,reason,revoked_by) VALUES(?,'build',?,?,?)",wid,buildId,reason,actor);
        forge.audit(wid,actor,"protection.build.revoked",buildId,reason); return Response.ok(Map.of("ok",true)).build();
    }

    @POST @Path("/protection/challenge")
    public Response challenge(Map<String,Object>b){
        String forgeKey=text(b,"forge_key"); if(forgeKey.isBlank()) forgeKey=text(b,"license");
        String slug=text(b,"product"); if(slug.isBlank()) slug=text(b,"product_slug");
        String build=text(b,"build_id"),resource=text(b,"resource_id"); if(resource.isBlank())resource=text(b,"resource_name");
        String nonce=text(b,"nonce"),fingerprint=text(b,"fingerprint"),keymaster=text(b,"keymaster");
        if(forgeKey.isBlank()||slug.isBlank()||build.isBlank()||resource.isBlank()||nonce.length()<16) return Response.status(400).entity(Map.of("ok",false,"valid",false,"error","invalid_protection_challenge")).build();
        Map<String,Object> lic=db.one("""
          SELECT l.*,u.id user_id,p.id product_id,p.workspace_id,p.slug,p.name product_name
          FROM users u JOIN licenses l ON l.user_id=u.id JOIN products p ON p.id=l.product_id
          WHERE u.forge_key=? AND p.slug=? AND l.status='active' AND p.status='published'
          """,forgeKey,slug);
        if(lic==null) return blocked(null,null,forgeKey,resource,keymaster,"license_invalid");
        Map<String,Object> buildRow=db.one("SELECT * FROM protection_builds WHERE build_id=? AND product_id=?",build,lic.get("product_id"));
        if(buildRow==null||!"active".equals(buildRow.get("status"))) return blocked(lic,lic,forgeKey,resource,keymaster,"protection_build_invalid");
        if(!keymaster.isBlank()){
            String kh=security.sha256(keymaster); if(db.count("SELECT COUNT(*) FROM user_keymasters WHERE user_id=? AND keymaster_hash=? AND status='active'",lic.get("user_id"),kh)==0) return blocked(lic,lic,forgeKey,resource,keymaster,"keymaster_not_registered");
        }
        if(db.count("SELECT COUNT(*) FROM protection_sessions WHERE nonce_hash=?",security.sha256(nonce))>0) return Response.status(409).entity(Map.of("ok",false,"valid",false,"error","protection_replay_detected")).build();
        Map<String,Object> cfg=protection(((Number)lic.get("product_id")).longValue()); boolean bind=Boolean.TRUE.equals(cfg.get("fingerprint_binding"));
        String fpHash=fingerprint.isBlank()?"":security.sha256(fingerprint); if(bind&&fpHash.isBlank()) return blocked(lic,lic,forgeKey,resource,keymaster,"fingerprint_required");
        String installation=security.sha256(build+"|"+fpHash+"|"+resource).substring(0,48);
        Map<String,Object> existing=db.one("SELECT * FROM protection_installations WHERE installation_id=?",installation);
        if(existing!=null&&!"active".equals(existing.get("status"))) return blocked(lic,lic,forgeKey,resource,keymaster,"installation_revoked");
        if(existing==null) db.insert("INSERT INTO protection_installations(installation_id,workspace_id,product_id,release_id,license_id,build_id,resource_id,fingerprint_hash,fingerprint_masked,version,status) VALUES(?,?,?,?,?,?,?,?,?,?,'active')",
            installation,lic.get("workspace_id"),lic.get("product_id"),buildRow.get("release_id"),lic.get("id"),build,resource,fpHash,mask(fingerprint),text(b,"version"));
        else db.execute("UPDATE protection_installations SET last_seen_at=CURRENT_TIMESTAMP,version=? WHERE installation_id=?",text(b,"version"),installation);
        int ttl=((Number)cfg.get("session_ttl_seconds")).intValue(); String sessionId=security.randomKey("NFS"),nonceHash=security.sha256(nonce);
        db.insert("INSERT INTO protection_sessions(session_id,build_id,installation_id,license_id,nonce_hash,expires_at,status) VALUES(?,?,?,?,?,?, 'active')",sessionId,build,installation,lic.get("id"),nonceHash,Timestamp.from(Instant.now().plus(ttl,ChronoUnit.SECONDS)));
        logLicense(lic,forgeKey,resource,keymaster,"valid","protection_challenge");
        Map<String,Object> out=new LinkedHashMap<>();out.put("ok",true);out.put("valid",true);out.put("session_id",sessionId);out.put("build_id",build);out.put("resource_id",resource);out.put("mode",buildRow.get("mode"));out.put("manifest",readMap(String.valueOf(buildRow.get("manifest_json"))));out.put("manifest_signature",buildRow.get("manifest_signature"));out.put("manifest_sha256",buildRow.get("manifest_sha256"));out.put("heartbeat_seconds",cfg.get("heartbeat_interval_seconds"));out.put("grace_period_seconds",cfg.get("grace_period_seconds"));out.put("decrypt_key",security.hmac(protectionSecret,forgeKey+"|"+slug+"|"+lic.get("id")+"|"+build));
        return Response.ok(out).build();
    }

    @POST @Path("/protection/heartbeat")
    public Response heartbeat(Map<String,Object>b){
        String session=text(b,"session_id"),nonce=text(b,"nonce"); if(session.isBlank()||nonce.isBlank()) return Response.status(400).entity(Map.of("ok",false,"valid",false,"error","invalid_protection_session")).build();
        Map<String,Object> s=db.one("""
          SELECT ps.*,pb.status build_status,pi.status installation_status,pp.heartbeat_interval_seconds,pp.grace_period_seconds,l.status license_status
          FROM protection_sessions ps JOIN protection_builds pb ON pb.build_id=ps.build_id JOIN protection_installations pi ON pi.installation_id=ps.installation_id
          JOIN licenses l ON l.id=ps.license_id LEFT JOIN product_protection pp ON pp.product_id=pb.product_id
          WHERE ps.session_id=?
          """,session);
        if(s==null||!"active".equals(s.get("status"))||!"active".equals(s.get("build_status"))||!"active".equals(s.get("installation_status"))||!"active".equals(s.get("license_status"))) return Response.status(403).entity(Map.of("ok",false,"valid",false,"error","protection_revoked")).build();
        Timestamp expires=(Timestamp)s.get("expires_at"); if(expires.toInstant().isBefore(Instant.now())) return Response.status(403).entity(Map.of("ok",false,"valid",false,"error","protection_session_expired")).build();
        if(!MessageDigest.isEqual(String.valueOf(s.get("nonce_hash")).getBytes(StandardCharsets.UTF_8),security.sha256(nonce).getBytes(StandardCharsets.UTF_8))) return Response.status(403).entity(Map.of("ok",false,"valid",false,"error","protection_nonce_mismatch")).build();
        db.execute("UPDATE protection_sessions SET last_heartbeat_at=CURRENT_TIMESTAMP WHERE session_id=?",session); db.execute("UPDATE protection_installations SET last_seen_at=CURRENT_TIMESTAMP WHERE installation_id=?",s.get("installation_id"));
        int hb=s.get("heartbeat_interval_seconds")==null?300:((Number)s.get("heartbeat_interval_seconds")).intValue(),grace=s.get("grace_period_seconds")==null?900:((Number)s.get("grace_period_seconds")).intValue();
        return Response.ok(Map.of("ok",true,"valid",true,"session_id",session,"heartbeat_seconds",hb,"grace_period_seconds",grace)).build();
    }

    @POST @Path("/protection/module")
    public Response module(Map<String,Object>b){
        String session=text(b,"session_id"),module=text(b,"module_id"),version=text(b,"version"); if(session.isBlank()||module.isBlank()||version.isBlank()) throw new BadRequestException("invalid_module_request");
        Map<String,Object> s=db.one("SELECT ps.*,pb.status build_status FROM protection_sessions ps JOIN protection_builds pb ON pb.build_id=ps.build_id WHERE ps.session_id=?",session);
        if(s==null||!"active".equals(s.get("status"))||!"active".equals(s.get("build_status"))) throw new ForbiddenException("protection_session_invalid");
        Timestamp expires=(Timestamp)s.get("expires_at"); if(expires.toInstant().isBefore(Instant.now())) throw new ForbiddenException("protection_session_expired");
        Map<String,Object> m=db.one("SELECT * FROM protection_modules WHERE build_id=? AND module_id=? AND version=? AND streamed=TRUE",s.get("build_id"),module,version); if(m==null) throw new NotFoundException("protection_module_not_found");
        try{
            byte[] bytes=Files.readAllBytes(Path.of(String.valueOf(m.get("storage_path")))); String sha=security.sha256(Base64.getEncoder().encodeToString(bytes)); if(!MessageDigest.isEqual(sha.getBytes(StandardCharsets.UTF_8),String.valueOf(m.get("sha256")).getBytes(StandardCharsets.UTF_8))) throw new ForbiddenException("protection_module_integrity_failed");
            return Response.ok(Map.of("ok",true,"module_id",module,"version",version,"build_id",s.get("build_id"),"sha256",sha,"signature",String.valueOf(m.get("signature")),"payload",Base64.getEncoder().encodeToString(bytes))).build();
        }catch(NoSuchFileException e){throw new NotFoundException("protection_module_missing");}catch(Exception e){if(e instanceof WebApplicationException w)throw w;throw new IllegalStateException("module_read_failed",e);}
    }

    private Map<String,Object> product(long pid){Map<String,Object> p=db.one("SELECT * FROM products WHERE id=?",pid);if(p==null)throw new NotFoundException("product_not_found");return p;}
    private Map<String,Object> protection(long pid){
        Map<String,Object> p=db.one("SELECT * FROM product_protection WHERE product_id=?",pid);
        if(p!=null)return p;
        Map<String,Object> d=new LinkedHashMap<>();d.put("product_id",pid);d.put("protection_level","standard");d.put("integrity_validation",true);d.put("streamed_modules_json","[]");d.put("heartbeat_enabled",true);d.put("heartbeat_interval_seconds",300);d.put("grace_period_seconds",900);d.put("fingerprint_binding",true);d.put("session_ttl_seconds",300);d.put("watermarking_enabled",true);d.put("minimum_version","");d.put("downgrade_block",false);return d;
    }
    private Response blocked(Map<String,Object> lic,Map<String,Object> row,String forgeKey,String resource,String keymaster,String reason){if(lic!=null)logLicense(lic,forgeKey,resource,keymaster,"blocked",reason);return Response.status(403).entity(Map.of("ok",false,"valid",false,"error",reason)).build();}
    private void logLicense(Map<String,Object> lic,String forgeKey,String resource,String keymaster,String result,String reason){db.insert("INSERT INTO license_logs(license_id,user_id,product_id,forge_key_prefix,server_id,keymaster_hash,resource_name,result,reason) VALUES(?,?,?,?,?,?,?,?,?)",lic.get("id"),lic.get("user_id"),lic.get("product_id"),forgeKey.length()>18?forgeKey.substring(0,18):forgeKey,resource,keymaster.isBlank()?"":security.sha256(keymaster),resource,result,reason);}
    private String mask(String s){if(s==null||s.isBlank())return "";return s.length()<=8?"***":s.substring(0,4)+"…"+s.substring(s.length()-4);}
    private String write(Object o){try{return json.writeValueAsString(o);}catch(Exception e){throw new IllegalStateException(e);}}
    @SuppressWarnings("unchecked") private Map<String,Object> readMap(String s){try{Object o=json.readValue(s,Map.class);return o instanceof Map<?,?>?(Map<String,Object>)o:new LinkedHashMap<>();}catch(Exception e){return new LinkedHashMap<>();}}
}
