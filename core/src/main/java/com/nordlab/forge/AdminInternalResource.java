package com.nordlab.forge;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.*;

@Path("/api/v2/internal/admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminInternalResource {
    @Inject Database db;
    @ConfigProperty(name="NORD_ADMIN_SERVICE_SECRET") String adminServiceSecret;

    private void internal(String secret){
        if(secret==null||!secret.equals(adminServiceSecret)) throw new NotAuthorizedException("admin_service");
    }

    private String setting(String key,String fallback){
        Map<String,Object> s=db.one("SELECT setting_value FROM platform_settings WHERE setting_key=?",key);
        return s==null?fallback:String.valueOf(s.get("setting_value"));
    }

    @GET @Path("/summary")
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

    @GET @Path("/users")
    public Response users(@HeaderParam("X-Nord-Admin-Service") String secret){
        internal(secret);
        return Response.ok(db.query("SELECT id,email,display_name,forge_key,platform_owner,status,created_at FROM users ORDER BY created_at DESC")).build();
    }

    @GET @Path("/workspaces")
    public Response workspaces(@HeaderParam("X-Nord-Admin-Service") String secret){
        internal(secret);
        return Response.ok(db.query("SELECT w.*,u.email owner_email FROM workspaces w JOIN users u ON u.id=w.owner_id ORDER BY w.created_at DESC")).build();
    }

    @PUT @Path("/maintenance")
    public Response maintenance(@HeaderParam("X-Nord-Admin-Service") String secret,Map<String,Object> b){
        internal(secret);
        String value=String.valueOf(Boolean.parseBoolean(String.valueOf(b.get("enabled"))));
        if(db.count("SELECT COUNT(*) FROM platform_settings WHERE setting_key='maintenance'")==0)
            db.execute("INSERT INTO platform_settings(setting_key,setting_value) VALUES('maintenance',?)",value);
        else
            db.execute("UPDATE platform_settings SET setting_value=?,updated_at=CURRENT_TIMESTAMP WHERE setting_key='maintenance'",value);
        return Response.ok(Map.of("ok",true,"enabled",Boolean.parseBoolean(value))).build();
    }

    @PUT @Path("/users/{uid}/status")
    public Response userStatus(@PathParam("uid") long uid,@HeaderParam("X-Nord-Admin-Service") String secret,Map<String,Object> b){
        internal(secret);
        Map<String,Object> u=db.one("SELECT id,platform_owner,status FROM users WHERE id=?",uid);
        if(u==null) throw new NotFoundException("user_not_found");
        if(Boolean.TRUE.equals(u.get("platform_owner"))) throw new BadRequestException("platform_owner_protected");
        String status=String.valueOf(b.getOrDefault("status","active"));
        if(!Set.of("active","suspended").contains(status)) throw new BadRequestException("invalid_status");
        db.execute("UPDATE users SET status=? WHERE id=?",status,uid);
        if("suspended".equals(status)) db.execute("DELETE FROM sessions WHERE user_id=?",uid);
        return Response.ok(Map.of("ok",true,"status",status)).build();
    }

    @PUT @Path("/workspaces/{wid}/status")
    public Response workspaceStatus(@PathParam("wid") long wid,@HeaderParam("X-Nord-Admin-Service") String secret,Map<String,Object> b){
        internal(secret);
        if(db.count("SELECT COUNT(*) FROM workspaces WHERE id=?",wid)==0) throw new NotFoundException("workspace_not_found");
        String status=String.valueOf(b.getOrDefault("status","active"));
        if(!Set.of("active","suspended","archived").contains(status)) throw new BadRequestException("invalid_status");
        db.execute("UPDATE workspaces SET status=? WHERE id=?",status,wid);
        return Response.ok(Map.of("ok",true,"status",status)).build();
    }

    @GET @Path("/audit")
    public Response audit(@HeaderParam("X-Nord-Admin-Service") String secret){
        internal(secret);
        return Response.ok(db.query("SELECT ae.*,u.email,u.display_name,w.name workspace_name FROM audit_events ae LEFT JOIN users u ON u.id=ae.user_id LEFT JOIN workspaces w ON w.id=ae.workspace_id ORDER BY ae.created_at DESC LIMIT 500")).build();
    }

    @GET @Path("/runtime")
    public Response runtime(@HeaderParam("X-Nord-Admin-Service") String secret){
        internal(secret);
        Runtime rt=Runtime.getRuntime();
        return Response.ok(Map.of(
            "ok",true,
            "version","2.0.0",
            "java",System.getProperty("java.version"),
            "processors",rt.availableProcessors(),
            "memory_max",rt.maxMemory(),
            "memory_total",rt.totalMemory(),
            "memory_free",rt.freeMemory(),
            "maintenance",Boolean.parseBoolean(setting("maintenance","false"))
        )).build();
    }
}
