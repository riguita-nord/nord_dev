package com.nordlab.forge;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.io.InputStream;
import java.util.*;

@Path("/")
public class CompatibilityResource {
    @Inject Database db;
    @Inject SecurityService security;
    @Inject ForgeService forge;

    @GET @Path("health")
    @Produces(MediaType.APPLICATION_JSON)
    public Response legacyHealth(){
        return Response.ok(Map.of("ok",true,"service","nord-forge","version","2.0.0")).build();
    }

    @GET @Path("api/realtime/version")
    @Produces(MediaType.APPLICATION_JSON)
    public Response realtimeVersion(@CookieParam("NF_SESSION") String session){
        security.requireUser(session);
        long audit=db.count("SELECT COALESCE(MAX(id),0) FROM audit_events");
        return Response.ok(Map.of("ok",true,"version",audit,"platform","2.0.0")).build();
    }

    @GET @Path("api/bootstrap")
    @Produces(MediaType.APPLICATION_JSON)
    public Response legacyBootstrap(@CookieParam("NF_SESSION") String session){
        Map<String,Object> u=security.requireUser(session); long uid=forge.userId(u);
        List<Map<String,Object>> workspaces=db.query("""
          SELECT DISTINCT w.id,w.name,w.slug,w.status,CASE WHEN w.owner_id=? THEN 'owner' ELSE wm.role END my_role
          FROM workspaces w LEFT JOIN workspace_members wm ON wm.workspace_id=w.id AND wm.user_id=? AND wm.status='active'
          WHERE w.owner_id=? OR wm.user_id=? ORDER BY w.created_at DESC
          """,uid,uid,uid,uid);
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("ok",true); out.put("user",u); out.put("workspaces",workspaces);
        out.put("customer_products",db.count("SELECT COUNT(*) FROM entitlements WHERE user_id=? AND status='active'",uid));
        out.put("customer_licenses",db.count("SELECT COUNT(*) FROM licenses WHERE user_id=? AND status='active'",uid));
        out.put("marketplace_products",db.count("SELECT COUNT(*) FROM products WHERE status='published'"));
        return Response.ok(out).build();
    }

    @GET
    @Path("{route:app|dashboard|inicio|meus-produtos|produtos-comprados|marketplace|suporte|support|workspaces|licenses|products|settings|sistema}")
    @Produces(MediaType.TEXT_HTML)
    public Response legacySpaRoot(){
        return index();
    }

    @GET
    @Path("workspace/{route:dashboard|produtos|products|licencas|licenses|releases|docs|website|equipa|team|infraestrutura|infra|api|auditoria|audit}")
    @Produces(MediaType.TEXT_HTML)
    public Response legacyWorkspace(){
        return index();
    }

    @GET @Path("s/{slug}")
    public Response legacyStore(@PathParam("slug") String slug){
        return Response.seeOther(java.net.URI.create("/store.html?slug="+url(slug))).build();
    }

    private Response index(){
        InputStream in=Thread.currentThread().getContextClassLoader().getResourceAsStream("META-INF/resources/index.html");
        if(in==null) throw new NotFoundException();
        return Response.ok(in).type(MediaType.TEXT_HTML_TYPE).build();
    }

    private String url(String value){
        return java.net.URLEncoder.encode(value,java.nio.charset.StandardCharsets.UTF_8);
    }
}
