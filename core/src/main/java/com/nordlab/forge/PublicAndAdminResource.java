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

}
