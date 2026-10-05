package com.nordlab.forge;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.util.*;

@Path("/api/license")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LegacyLicenseResource {
    @Inject Database db;
    @Inject SecurityService security;

    @POST @Path("/validate")
    public Response validate(Map<String,Object> b){ return doValidate(b,false); }

    @POST @Path("/session")
    public Response session(Map<String,Object> b){ return doValidate(b,true); }

    private Response doValidate(Map<String,Object> b,boolean session){
        if(b==null)b=new HashMap<>();
        String forgeKey=text(b,"forge_key"), slug=text(b,"product_slug");
        if(slug.isBlank()) slug=text(b,"product");
        String serverId=text(b,"server_id");
        if(serverId.isBlank()) serverId=text(b,"server_ip");
        if(serverId.isBlank()) serverId=text(b,"keymaster");
        String keymaster=text(b,"keymaster");
        if(forgeKey.isBlank()||slug.isBlank()||serverId.isBlank()) return Response.status(400).entity(Map.of("valid",false,"ok",false,"error","missing_fields")).build();
        Map<String,Object> row=db.one("""
          SELECT l.id license_id,l.status license_status,l.server_limit,p.id product_id,p.name,p.slug,p.license_required,p.protection_mode
          FROM users u JOIN licenses l ON l.user_id=u.id JOIN products p ON p.id=l.product_id
          WHERE u.forge_key=? AND p.slug=? AND p.status='published'
          """,forgeKey,slug);
        if(row==null||!"active".equals(row.get("license_status"))) return Response.status(403).entity(Map.of("valid",false,"ok",false,"error","license_invalid")).build();
        long lid=((Number)row.get("license_id")).longValue();
        Map<String,Object> act=db.one("SELECT id,revoked FROM license_activations WHERE license_id=? AND server_id=?",lid,serverId);
        if(act==null){
            long active=db.count("SELECT COUNT(*) FROM license_activations WHERE license_id=? AND revoked=FALSE",lid);
            if(active>=((Number)row.get("server_limit")).longValue()) return Response.status(403).entity(Map.of("valid",false,"ok",false,"error","server_limit")).build();
            db.insert("INSERT INTO license_activations(license_id,server_id,keymaster) VALUES(?,?,?)",lid,serverId,keymaster);
        }else{
            if(Boolean.TRUE.equals(act.get("revoked"))) return Response.status(403).entity(Map.of("valid",false,"ok",false,"error","activation_revoked")).build();
            db.execute("UPDATE license_activations SET keymaster=?,last_seen_at=CURRENT_TIMESTAMP WHERE id=?",keymaster,act.get("id"));
        }
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("ok",true); out.put("valid",true); out.put("product",row.get("slug")); out.put("protection_mode",row.get("protection_mode"));
        if(session){ out.put("decrypt_key",security.randomKey("nfs")); out.put("ttl_seconds",360); }
        return Response.ok(out).build();
    }
    private String text(Map<String,Object>b,String k){Object v=b.get(k);return v==null?"":String.valueOf(v).trim();}
}
