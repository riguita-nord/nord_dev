package com.nordlab.forge;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.Base64;

@Path("/")
public class PublicAndAdminResource {
    @Inject Database db;
    @Inject SecurityService security;
    @Inject StorageService storage;

    @ConfigProperty(name="NORD_ADMIN_URL",defaultValue="http://127.0.0.1:8089") String adminUrl;
    @ConfigProperty(name="NORD_ADMIN_SSO_SECRET") String adminSsoSecret;
    @ConfigProperty(name="NORD_ADMIN_SERVICE_SECRET") String adminServiceSecret;

    @GET @Path("download/{token}")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response download(@PathParam("token") String token){
        Map<String,Object> d=db.one("""
          SELECT dt.id token_id,dt.used_at,dt.expires_at,r.id release_id,r.file_name,r.storage_path
          FROM download_tokens dt JOIN releases r ON r.id=dt.release_id
          WHERE dt.token_hash=? AND dt.expires_at>CURRENT_TIMESTAMP
          """,security.sha256(token));
        if(d==null||d.get("used_at")!=null) throw new NotFoundException("download_expired");
        db.execute("UPDATE download_tokens SET used_at=CURRENT_TIMESTAMP WHERE id=?",d.get("token_id"));
        byte[] bytes=storage.read(String.valueOf(d.get("storage_path")));
        return Response.ok(bytes).type("application/zip").header("Content-Disposition","attachment; filename=\""+String.valueOf(d.get("file_name")).replace("\"","")+"\"").build();
    }

    @GET @Path("api/v2/public/stores/{slug}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response store(@PathParam("slug") String slug){
        Map<String,Object> w=db.one("SELECT id,name,slug,store_name,store_description,store_currency,store_theme FROM workspaces WHERE slug=? AND status='active'",slug);
        if(w==null) throw new NotFoundException("store_not_found");
        long wid=((Number)w.get("id")).longValue();
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("store",w);
        out.put("products",db.query("SELECT p.id,p.name,p.slug,p.description,p.category,p.price_cents,p.currency,p.license_required FROM products p WHERE p.workspace_id=? AND p.status='published' AND EXISTS(SELECT 1 FROM releases r WHERE r.product_id=p.id AND r.published=TRUE) ORDER BY p.created_at DESC",wid));
        out.put("pages",db.query("SELECT title,slug,layout_json,theme FROM site_pages WHERE workspace_id=? AND published=TRUE ORDER BY updated_at DESC",wid));
        out.put("docs",db.query("SELECT title,slug,body FROM docs WHERE workspace_id=? AND published=TRUE ORDER BY updated_at DESC",wid));
        return Response.ok(out).build();
    }

    @GET @Path("api/v2/admin/launch")
    @Produces(MediaType.APPLICATION_JSON)
    public Response adminLaunch(@CookieParam("NF_SESSION") String session){
        Map<String,Object> u=security.requireUser(session);
        if(!Boolean.TRUE.equals(u.get("platform_owner"))) throw new ForbiddenException("platform_owner_required");
        long exp=Instant.now().plusSeconds(90).getEpochSecond();
        String payload=u.get("id")+":"+Base64.getUrlEncoder().withoutPadding().encodeToString(String.valueOf(u.get("email")).getBytes(StandardCharsets.UTF_8))+":"+exp;
        String enc=Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String sig=security.hmac(adminSsoSecret,enc);
        return Response.ok(Map.of("ok",true,"url",adminUrl+"/?token="+enc+"."+sig)).build();
    }

    private void internal(String secret){ if(secret==null||!secret.equals(adminServiceSecret)) throw new NotAuthorizedException("admin_service"); }

    @GET @Path("api/v2/internal/admin/summary")
    @Produces(MediaType.APPLICATION_JSON)
    public Response summary(@HeaderParam("X-Nord-Admin-Service") String secret){
        internal(secret);
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("users",db.count("SELECT COUNT(*) FROM users"));
        out.put("workspaces",db.count("SELECT COUNT(*) FROM workspaces"));
        out.put("products",db.count("SELECT COUNT(*) FROM products"));
        out.put("published_products",db.count("SELECT COUNT(*) FROM products WHERE status='published'"));
        out.put("active_licenses",db.count("SELECT COUNT(*) FROM licenses WHERE status='active'"));
        out.put("open_support",db.count("SELECT COUNT(*) FROM support_tickets WHERE status='open'"));
        out.put("open_purchases",db.count("SELECT COUNT(*) FROM purchase_threads WHERE status='open'"));
        out.put("users_recent",db.query("SELECT id,email,display_name,forge_key,platform_owner,status,created_at FROM users ORDER BY created_at DESC LIMIT 12"));
        out.put("workspaces_recent",db.query("SELECT w.*,u.email owner_email FROM workspaces w JOIN users u ON u.id=w.owner_id ORDER BY w.created_at DESC LIMIT 12"));
        out.put("maintenance",setting("maintenance","false"));
        return Response.ok(out).build();
    }

    @GET @Path("api/v2/internal/admin/users")
    @Produces(MediaType.APPLICATION_JSON)
    public Response users(@HeaderParam("X-Nord-Admin-Service") String secret){ internal(secret); return Response.ok(db.query("SELECT id,email,display_name,forge_key,platform_owner,status,created_at FROM users ORDER BY created_at DESC")).build(); }

    @GET @Path("api/v2/internal/admin/workspaces")
    @Produces(MediaType.APPLICATION_JSON)
    public Response workspaces(@HeaderParam("X-Nord-Admin-Service") String secret){ internal(secret); return Response.ok(db.query("SELECT w.*,u.email owner_email FROM workspaces w JOIN users u ON u.id=w.owner_id ORDER BY w.created_at DESC")).build(); }

    @PUT @Path("api/v2/internal/admin/maintenance")
    @Consumes(MediaType.APPLICATION_JSON) @Produces(MediaType.APPLICATION_JSON)
    public Response maintenance(@HeaderParam("X-Nord-Admin-Service") String secret,Map<String,Object> b){
        internal(secret); String value=String.valueOf(Boolean.parseBoolean(String.valueOf(b.get("enabled"))));
        if(db.count("SELECT COUNT(*) FROM platform_settings WHERE setting_key='maintenance'")==0) db.execute("INSERT INTO platform_settings(setting_key,setting_value) VALUES('maintenance',?)",value);
        else db.execute("UPDATE platform_settings SET setting_value=?,updated_at=CURRENT_TIMESTAMP WHERE setting_key='maintenance'",value);
        return Response.ok(Map.of("ok",true,"enabled",Boolean.parseBoolean(value))).build();
    }

    private String setting(String key,String fallback){ Map<String,Object> s=db.one("SELECT setting_value FROM platform_settings WHERE setting_key=?",key); return s==null?fallback:String.valueOf(s.get("setting_value")); }
}
