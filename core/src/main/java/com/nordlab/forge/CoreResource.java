package com.nordlab.forge;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Path("/api/v2")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CoreResource {
    @Inject Database db;
    @Inject SecurityService security;
    @Inject ForgeService forge;
    @Inject StorageService storage;
    @ConfigProperty(name="NORD_ADMIN_URL",defaultValue="/administration") String adminUrl;
    @ConfigProperty(name="NORD_ADMIN_SSO_SECRET") String adminSsoSecret;

    private Map<String,Object> user(String session){ return security.requireUser(session); }
    private long uid(String session){ return forge.userId(user(session)); }
    private void unsafe(String session,String csrf){ security.requireCsrf(session,csrf); }
    private Response ok(Object value){ return Response.ok(value).build(); }
    private Map<String,Object> body(Map<String,Object> b){ return b==null?new HashMap<>():b; }

    @GET @Path("/setup/status")
    public Response setupStatus(){
        long users=db.count("SELECT COUNT(*) FROM users");
        return ok(Map.of(
            "ok",true,
            "needs_setup",users==0,
            "registration_enabled",true,
            "users",users
        ));
    }

    @POST @Path("/auth/register")
    public Response register(Map<String,Object> b){
        b=body(b); var s=security.register(forge.text(b,"email"),forge.text(b,"password"),forge.text(b,"display_name"));
        return Response.ok(Map.of("ok",true,"user",s.user(),"csrf",s.csrf())).header("Set-Cookie",security.cookie(s.token())).build();
    }
    @POST @Path("/auth/login")
    public Response login(Map<String,Object> b){
        b=body(b); var s=security.login(forge.text(b,"email"),forge.text(b,"password"));
        return Response.ok(Map.of("ok",true,"user",s.user(),"csrf",s.csrf())).header("Set-Cookie",security.cookie(s.token())).build();
    }
    @POST @Path("/auth/logout")
    public Response logout(@CookieParam("NF_SESSION") String session){ security.logout(session); return Response.ok(Map.of("ok",true)).header("Set-Cookie",security.clearCookie()).build(); }

    @GET @Path("/admin/launch")
    public Response adminLaunch(@CookieParam("NF_SESSION") String session){
        Map<String,Object> u=security.requireUser(session);
        if(!Boolean.TRUE.equals(u.get("platform_owner"))) throw new ForbiddenException("platform_owner_required");
        long exp=Instant.now().plusSeconds(90).getEpochSecond();
        String payload=u.get("id")+":"+Base64.getUrlEncoder().withoutPadding().encodeToString(String.valueOf(u.get("email")).getBytes(StandardCharsets.UTF_8))+":"+exp;
        String enc=Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String sig=security.hmac(adminSsoSecret,enc);
        return ok(Map.of("ok",true,"url",adminUrl+"/?token="+enc+"."+sig));
    }
    @GET @Path("/me")
    public Response me(@CookieParam("NF_SESSION") String session){
        Map<String,Object> u=user(session); return ok(Map.of("ok",true,"user",u,"csrf",u.get("csrf_token")));
    }

    @GET @Path("/workspaces")
    public Response workspaces(@CookieParam("NF_SESSION") String session){
        long id=uid(session);
        return ok(db.query("""
            SELECT DISTINCT w.*,CASE WHEN w.owner_id=? THEN 'owner' ELSE wm.role END AS my_role
            FROM workspaces w LEFT JOIN workspace_members wm ON wm.workspace_id=w.id AND wm.user_id=? AND wm.status='active'
            WHERE w.owner_id=? OR wm.user_id=? ORDER BY w.created_at DESC
            """,id,id,id,id));
    }
    @POST @Path("/workspaces")
    public Response createWorkspace(@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf,Map<String,Object> b){
        unsafe(session,csrf); long id=uid(session); b=body(b); String name=forge.text(b,"name");
        if(name.length()<2) throw new BadRequestException("workspace_name_required");
        String requestedSlug=forge.text(b,"slug");
        String slug=requestedSlug.isBlank()?forge.uniqueWorkspaceSlug(name):forge.slug(requestedSlug);
        if(slug.length()<2) slug=forge.uniqueWorkspaceSlug(name);
        if(db.count("SELECT COUNT(*) FROM workspaces WHERE slug=?",slug)>0) slug=forge.uniqueWorkspaceSlug(name);
        String storeName=forge.text(b,"store_name"); if(storeName.isBlank()) storeName=name;
        String currency=forge.text(b,"currency"); if(currency.isBlank()) currency="EUR";
        String theme=forge.text(b,"theme"); if(!"light".equals(theme)&&!"dark".equals(theme)) theme="dark";
        long wid=db.insert("INSERT INTO workspaces(owner_id,name,slug,store_name,store_description,store_currency,store_theme) VALUES(?,?,?,?,?,?,?)",
            id,name,slug,storeName,forge.text(b,"store_description"),currency,theme);
        forge.audit(wid,id,"workspace.created",String.valueOf(wid),null);
        return ok(forge.workspace(wid));
    }

    @POST @Path("/workspaces/{wid}/archive")
    public Response archive(@PathParam("wid") long wid,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf){
        unsafe(session,csrf); long id=uid(session); forge.requireWorkspace(id,wid,"admin"); db.execute("UPDATE workspaces SET status='archived' WHERE id=?",wid); forge.audit(wid,id,"workspace.archived",String.valueOf(wid),null); return ok(Map.of("ok",true));
    }
    @POST @Path("/workspaces/{wid}/restore")
    public Response restore(@PathParam("wid") long wid,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf){
        unsafe(session,csrf); long id=uid(session); forge.requireWorkspace(id,wid,"admin"); db.execute("UPDATE workspaces SET status='active' WHERE id=?",wid); forge.audit(wid,id,"workspace.restored",String.valueOf(wid),null); return ok(Map.of("ok",true));
    }
    @GET @Path("/workspaces/{wid}/dashboard")
    public Response dashboard(@PathParam("wid") long wid,@CookieParam("NF_SESSION") String session){
        long id=uid(session); forge.requireWorkspace(id,wid);
        Map<String,Object> out=new LinkedHashMap<>(); out.put("workspace",forge.workspace(wid)); out.put("role",forge.role(id,wid));
        out.put("products",db.count("SELECT COUNT(*) FROM products WHERE workspace_id=?",wid));
        out.put("published_products",db.count("SELECT COUNT(*) FROM products WHERE workspace_id=? AND status='published'",wid));
        out.put("licenses",db.count("SELECT COUNT(*) FROM licenses l JOIN products p ON p.id=l.product_id WHERE p.workspace_id=? AND l.status='active'",wid));
        out.put("members",db.count("SELECT COUNT(*) FROM workspace_members WHERE workspace_id=? AND status='active'",wid)+1);
        out.put("open_support",db.count("SELECT COUNT(*) FROM support_tickets WHERE workspace_id=? AND status='open'",wid));
        out.put("open_purchases",db.count("SELECT COUNT(*) FROM purchase_threads WHERE workspace_id=? AND status='open'",wid));
        return ok(out);
    }

    @GET @Path("/workspaces/{wid}/members")
    public Response members(@PathParam("wid") long wid,@CookieParam("NF_SESSION") String session){
        forge.requireWorkspace(uid(session),wid);
        return ok(db.query("SELECT wm.id,wm.user_id,u.email,u.display_name,wm.role,wm.permissions,wm.status,wm.created_at FROM workspace_members wm JOIN users u ON u.id=wm.user_id WHERE wm.workspace_id=? ORDER BY wm.created_at",wid));
    }
    @POST @Path("/workspaces/{wid}/members")
    public Response addMember(@PathParam("wid") long wid,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf,Map<String,Object> b){
        unsafe(session,csrf); long actor=uid(session); forge.requireWorkspace(actor,wid,"admin"); b=body(b);
        Map<String,Object> target=db.one("SELECT id FROM users WHERE email=?",forge.text(b,"email").toLowerCase(Locale.ROOT)); if(target==null) throw new NotFoundException("user_not_found");
        long targetId=((Number)target.get("id")).longValue(); String role=forge.text(b,"role"); if(role.isBlank()) role="developer";
        Map<String,Object> exists=db.one("SELECT id FROM workspace_members WHERE workspace_id=? AND user_id=?",wid,targetId);
        if(exists==null) db.insert("INSERT INTO workspace_members(workspace_id,user_id,role,permissions) VALUES(?,?,?,?)",wid,targetId,role,forge.text(b,"permissions"));
        else db.execute("UPDATE workspace_members SET role=?,permissions=?,status='active' WHERE id=?",role,forge.text(b,"permissions"),exists.get("id"));
        forge.audit(wid,actor,"member.upserted",String.valueOf(targetId),role); return ok(Map.of("ok",true));
    }
    @PUT @Path("/workspaces/{wid}/members/{mid}")
    public Response updateMember(@PathParam("wid") long wid,@PathParam("mid") long mid,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf,Map<String,Object> b){
        unsafe(session,csrf); long actor=uid(session); forge.requireWorkspace(actor,wid,"admin"); b=body(b); db.execute("UPDATE workspace_members SET role=?,permissions=? WHERE id=? AND workspace_id=?",forge.text(b,"role"),forge.text(b,"permissions"),mid,wid); forge.audit(wid,actor,"member.updated",String.valueOf(mid),null); return ok(Map.of("ok",true));
    }
    @DELETE @Path("/workspaces/{wid}/members/{mid}")
    public Response removeMember(@PathParam("wid") long wid,@PathParam("mid") long mid,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf){
        unsafe(session,csrf); long actor=uid(session); forge.requireWorkspace(actor,wid,"admin"); db.execute("DELETE FROM workspace_members WHERE id=? AND workspace_id=?",mid,wid); forge.audit(wid,actor,"member.removed",String.valueOf(mid),null); return ok(Map.of("ok",true));
    }

    @GET @Path("/workspaces/{wid}/products")
    public Response products(@PathParam("wid") long wid,@CookieParam("NF_SESSION") String session){ forge.requireWorkspace(uid(session),wid); return ok(db.query("SELECT * FROM products WHERE workspace_id=? ORDER BY created_at DESC",wid)); }
    @POST @Path("/workspaces/{wid}/products")
    public Response createProduct(@PathParam("wid") long wid,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf,Map<String,Object> b){
        unsafe(session,csrf); long actor=uid(session); forge.requireWorkspace(actor,wid,"developer","admin"); b=body(b); String name=forge.text(b,"name"); if(name.length()<2) throw new BadRequestException("product_name_required");
        long pid=db.insert("INSERT INTO products(workspace_id,name,slug,description,category,price_cents,currency,license_required,protection_mode,status) VALUES(?,?,?,?,?,?,?,?,?,?)",
            wid,name,forge.slug(forge.text(b,"slug").isBlank()?name:forge.text(b,"slug")),forge.text(b,"description"),forge.text(b,"category"),forge.longValue(b,"price_cents",0),forge.text(b,"currency").isBlank()?"EUR":forge.text(b,"currency"),forge.bool(b,"license_required",true),forge.text(b,"protection_mode").isBlank()?"LICENSE_ONLY":forge.text(b,"protection_mode"),"draft");
        forge.audit(wid,actor,"product.created",String.valueOf(pid),name); return ok(db.one("SELECT * FROM products WHERE id=?",pid));
    }
    @PUT @Path("/products/{pid}")
    public Response updateProduct(@PathParam("pid") long pid,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf,Map<String,Object> b){
        unsafe(session,csrf); Map<String,Object> p=db.one("SELECT * FROM products WHERE id=?",pid); if(p==null) throw new NotFoundException("product_not_found");
        long wid=((Number)p.get("workspace_id")).longValue(), actor=uid(session); forge.requireWorkspace(actor,wid,"developer","admin"); b=body(b);
        String status=forge.text(b,"status"); if(status.isBlank()) status=String.valueOf(p.get("status"));
        if("published".equals(status)&&db.count("SELECT COUNT(*) FROM releases WHERE product_id=? AND published=TRUE",pid)==0) throw new BadRequestException("published_release_required");
        db.execute("UPDATE products SET name=?,description=?,category=?,price_cents=?,currency=?,license_required=?,protection_mode=?,status=? WHERE id=?",
            forge.text(b,"name").isBlank()?p.get("name"):forge.text(b,"name"),forge.text(b,"description"),forge.text(b,"category"),forge.longValue(b,"price_cents",((Number)p.get("price_cents")).longValue()),forge.text(b,"currency").isBlank()?p.get("currency"):forge.text(b,"currency"),forge.bool(b,"license_required",Boolean.TRUE.equals(p.get("license_required"))),forge.text(b,"protection_mode").isBlank()?p.get("protection_mode"):forge.text(b,"protection_mode"),status,pid);
        forge.audit(wid,actor,"product.updated",String.valueOf(pid),status); return ok(db.one("SELECT * FROM products WHERE id=?",pid));
    }
    @GET @Path("/workspaces/{wid}/releases")
    public Response workspaceReleases(@PathParam("wid") long wid,@CookieParam("NF_SESSION") String session){
        forge.requireWorkspace(uid(session),wid);
        return ok(db.query("""
          SELECT r.id,r.product_id,p.name product_name,r.version,r.changelog,r.file_name,r.published,r.created_at
          FROM releases r
          JOIN products p ON p.id=r.product_id
          WHERE p.workspace_id=?
          ORDER BY r.created_at DESC
          """,wid));
    }

    @GET @Path("/products/{pid}/releases")
    public Response releases(@PathParam("pid") long pid,@CookieParam("NF_SESSION") String session){
        Map<String,Object> p=db.one("SELECT workspace_id FROM products WHERE id=?",pid); if(p==null) throw new NotFoundException("product_not_found"); forge.requireWorkspace(uid(session),((Number)p.get("workspace_id")).longValue()); return ok(db.query("SELECT id,product_id,version,changelog,file_name,published,created_at FROM releases WHERE product_id=? ORDER BY created_at DESC",pid));
    }
    @POST @Path("/products/{pid}/releases")
    public Response createRelease(@PathParam("pid") long pid,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf,Map<String,Object> b){
        unsafe(session,csrf); Map<String,Object> p=db.one("SELECT * FROM products WHERE id=?",pid); if(p==null) throw new NotFoundException("product_not_found"); long wid=((Number)p.get("workspace_id")).longValue(),actor=uid(session); forge.requireWorkspace(actor,wid,"developer","admin"); b=body(b);
        String version=forge.text(b,"version"), file=forge.text(b,"file_name"); if(version.isBlank()) throw new BadRequestException("version_required");
        String path=storage.saveRelease(wid,pid,version,file,forge.text(b,"file_base64"));
        long rid=db.insert("INSERT INTO releases(product_id,version,changelog,file_name,storage_path,published) VALUES(?,?,?,?,?,?)",pid,version,forge.text(b,"changelog"),file,path,forge.bool(b,"published",true));
        forge.audit(wid,actor,"release.created",String.valueOf(rid),version); return ok(db.one("SELECT id,product_id,version,changelog,file_name,published,created_at FROM releases WHERE id=?",rid));
    }
    @PUT @Path("/releases/{rid}")
    public Response updateRelease(@PathParam("rid") long rid,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf,Map<String,Object> b){
        unsafe(session,csrf); Map<String,Object> r=db.one("SELECT r.*,p.workspace_id FROM releases r JOIN products p ON p.id=r.product_id WHERE r.id=?",rid); if(r==null) throw new NotFoundException("release_not_found"); long wid=((Number)r.get("workspace_id")).longValue(),actor=uid(session); forge.requireWorkspace(actor,wid,"developer","admin"); b=body(b);
        db.execute("UPDATE releases SET version=?,changelog=?,published=? WHERE id=?",forge.text(b,"version").isBlank()?r.get("version"):forge.text(b,"version"),forge.text(b,"changelog"),forge.bool(b,"published",Boolean.TRUE.equals(r.get("published"))),rid); forge.audit(wid,actor,"release.updated",String.valueOf(rid),null); return ok(Map.of("ok",true));
    }
    @DELETE @Path("/releases/{rid}")
    public Response deleteRelease(@PathParam("rid") long rid,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf){
        unsafe(session,csrf); Map<String,Object> r=db.one("SELECT r.*,p.workspace_id,p.status product_status FROM releases r JOIN products p ON p.id=r.product_id WHERE r.id=?",rid); if(r==null) throw new NotFoundException("release_not_found"); long wid=((Number)r.get("workspace_id")).longValue(),actor=uid(session); forge.requireWorkspace(actor,wid,"developer","admin");
        if("published".equals(r.get("product_status"))&&db.count("SELECT COUNT(*) FROM releases WHERE product_id=? AND published=TRUE",r.get("product_id"))<=1) throw new BadRequestException("cannot_delete_last_published_release");
        db.execute("DELETE FROM releases WHERE id=?",rid); storage.delete(String.valueOf(r.get("storage_path"))); forge.audit(wid,actor,"release.deleted",String.valueOf(rid),null); return ok(Map.of("ok",true));
    }

    @GET @Path("/client/products")
    public Response clientProducts(@CookieParam("NF_SESSION") String session){
        long id=uid(session); return ok(db.query("""
          SELECT p.*,w.name workspace_name,w.slug store_slug,e.source,e.status entitlement_status,
          (SELECT r.id FROM releases r WHERE r.product_id=p.id AND r.published=TRUE ORDER BY r.created_at DESC LIMIT 1) latest_release_id
          FROM entitlements e JOIN products p ON p.id=e.product_id JOIN workspaces w ON w.id=p.workspace_id
          WHERE e.user_id=? AND e.status='active' ORDER BY e.created_at DESC
          """,id));
    }
    @GET @Path("/client/licenses")
    public Response clientLicenses(@CookieParam("NF_SESSION") String session){ return ok(db.query("SELECT l.*,p.name product_name,p.slug product_slug FROM licenses l JOIN products p ON p.id=l.product_id WHERE l.user_id=? ORDER BY l.created_at DESC",uid(session))); }
    @GET @Path("/marketplace")
    public Response marketplace(@CookieParam("NF_SESSION") String session){
        uid(session); return ok(db.query("""
          SELECT p.id,p.name,p.slug,p.description,p.category,p.price_cents,p.currency,p.license_required,p.protection_mode,w.name workspace_name,w.slug store_slug
          FROM products p JOIN workspaces w ON w.id=p.workspace_id WHERE p.status='published' AND w.status='active'
          AND EXISTS(SELECT 1 FROM releases r WHERE r.product_id=p.id AND r.published=TRUE) ORDER BY p.created_at DESC
          """));
    }
    @POST @Path("/purchases")
    public Response startPurchase(@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf,Map<String,Object> b){
        unsafe(session,csrf); long buyer=uid(session); b=body(b); long pid=forge.longValue(b,"product_id",0); Map<String,Object> p=db.one("SELECT * FROM products WHERE id=? AND status='published'",pid); if(p==null) throw new NotFoundException("product_not_found");
        if(db.count("SELECT COUNT(*) FROM entitlements WHERE user_id=? AND product_id=? AND status='active'",buyer,pid)>0) throw new BadRequestException("already_owned");
        if(((Number)p.get("price_cents")).longValue()==0){ grant(buyer,pid,"free"); return ok(Map.of("ok",true,"granted",true)); }
        long thread=db.insert("INSERT INTO purchase_threads(workspace_id,product_id,buyer_id,provider) VALUES(?,?,?,'manual')",p.get("workspace_id"),pid,buyer);
        String message=forge.text(b,"message"); if(!message.isBlank()) db.insert("INSERT INTO purchase_messages(thread_id,user_id,message) VALUES(?,?,?)",thread,buyer,message);
        return ok(Map.of("ok",true,"thread_id",thread,"granted",false));
    }
    @GET @Path("/client/purchases")
    public Response myPurchases(@CookieParam("NF_SESSION") String session){ return ok(db.query("SELECT pt.*,p.name product_name,w.name workspace_name FROM purchase_threads pt JOIN products p ON p.id=pt.product_id JOIN workspaces w ON w.id=pt.workspace_id WHERE pt.buyer_id=? ORDER BY pt.created_at DESC",uid(session))); }
    @GET @Path("/workspaces/{wid}/purchases")
    public Response workspacePurchases(@PathParam("wid") long wid,@CookieParam("NF_SESSION") String session){ forge.requireWorkspace(uid(session),wid,"support","finance","developer","admin"); return ok(db.query("SELECT pt.*,p.name product_name,u.email buyer_email,u.display_name buyer_name FROM purchase_threads pt JOIN products p ON p.id=pt.product_id JOIN users u ON u.id=pt.buyer_id WHERE pt.workspace_id=? ORDER BY pt.created_at DESC",wid)); }
    @POST @Path("/workspaces/{wid}/purchases/{tid}/grant")
    public Response grantPurchase(@PathParam("wid") long wid,@PathParam("tid") long tid,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf){
        unsafe(session,csrf); long actor=uid(session); forge.requireWorkspace(actor,wid,"finance","admin"); Map<String,Object> t=db.one("SELECT * FROM purchase_threads WHERE id=? AND workspace_id=?",tid,wid); if(t==null) throw new NotFoundException("purchase_not_found"); grant(((Number)t.get("buyer_id")).longValue(),((Number)t.get("product_id")).longValue(),"manual"); db.execute("UPDATE purchase_threads SET status='granted' WHERE id=?",tid); forge.audit(wid,actor,"purchase.granted",String.valueOf(tid),null); return ok(Map.of("ok",true));
    }
    @GET @Path("/purchases/{tid}/messages")
    public Response purchaseMessages(@PathParam("tid") long tid,@CookieParam("NF_SESSION") String session){
        long actor=uid(session); Map<String,Object> t=db.one("SELECT * FROM purchase_threads WHERE id=?",tid); if(t==null) throw new NotFoundException("purchase_not_found"); if(((Number)t.get("buyer_id")).longValue()!=actor) forge.requireWorkspace(actor,((Number)t.get("workspace_id")).longValue(),"support","finance","developer","admin");
        return ok(db.query("SELECT pm.*,u.display_name,u.email FROM purchase_messages pm JOIN users u ON u.id=pm.user_id WHERE pm.thread_id=? ORDER BY pm.created_at",tid));
    }
    @POST @Path("/purchases/{tid}/messages")
    public Response purchaseMessage(@PathParam("tid") long tid,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf,Map<String,Object> b){
        unsafe(session,csrf); long actor=uid(session); Map<String,Object> t=db.one("SELECT * FROM purchase_threads WHERE id=?",tid); if(t==null) throw new NotFoundException("purchase_not_found"); if(((Number)t.get("buyer_id")).longValue()!=actor) forge.requireWorkspace(actor,((Number)t.get("workspace_id")).longValue(),"support","finance","developer","admin"); String msg=forge.text(body(b),"message"); if(msg.isBlank()) throw new BadRequestException("message_required"); db.insert("INSERT INTO purchase_messages(thread_id,user_id,message) VALUES(?,?,?)",tid,actor,msg); return ok(Map.of("ok",true));
    }

    @GET @Path("/workspaces/{wid}/licenses")
    public Response licenses(@PathParam("wid") long wid,@CookieParam("NF_SESSION") String session){ forge.requireWorkspace(uid(session),wid,"support","developer","admin"); return ok(db.query("SELECT l.*,u.email,u.display_name,p.name product_name,p.slug product_slug FROM licenses l JOIN users u ON u.id=l.user_id JOIN products p ON p.id=l.product_id WHERE p.workspace_id=? ORDER BY l.created_at DESC",wid)); }
    @POST @Path("/workspaces/{wid}/licenses")
    public Response grantLicense(@PathParam("wid") long wid,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf,Map<String,Object> b){
        unsafe(session,csrf); long actor=uid(session); forge.requireWorkspace(actor,wid,"support","admin"); b=body(b); Map<String,Object> u=db.one("SELECT id FROM users WHERE email=?",forge.text(b,"email").toLowerCase(Locale.ROOT)); if(u==null) throw new NotFoundException("user_not_found"); long pid=forge.longValue(b,"product_id",0); Map<String,Object> p=db.one("SELECT id FROM products WHERE id=? AND workspace_id=?",pid,wid); if(p==null) throw new NotFoundException("product_not_found"); grant(((Number)u.get("id")).longValue(),pid,"manual_license"); forge.audit(wid,actor,"license.granted",String.valueOf(pid),forge.text(b,"email")); return ok(Map.of("ok",true));
    }
    @PUT @Path("/workspaces/{wid}/licenses/{lid}")
    public Response updateLicense(@PathParam("wid") long wid,@PathParam("lid") long lid,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf,Map<String,Object> b){
        unsafe(session,csrf); long actor=uid(session); forge.requireWorkspace(actor,wid,"support","admin"); b=body(b); db.execute("UPDATE licenses SET status=?,server_limit=? WHERE id=? AND product_id IN(SELECT id FROM products WHERE workspace_id=?)",forge.text(b,"status").isBlank()?"active":forge.text(b,"status"),(int)forge.longValue(b,"server_limit",1),lid,wid); forge.audit(wid,actor,"license.updated",String.valueOf(lid),null); return ok(Map.of("ok",true));
    }

    @POST @Path("/client/releases/{rid}/token")
    public Response downloadToken(@PathParam("rid") long rid,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf){
        unsafe(session,csrf); long actor=uid(session); Map<String,Object> r=db.one("SELECT r.*,p.id product_id FROM releases r JOIN products p ON p.id=r.product_id WHERE r.id=? AND r.published=TRUE",rid); if(r==null) throw new NotFoundException("release_not_found");
        if(db.count("SELECT COUNT(*) FROM entitlements WHERE user_id=? AND product_id=? AND status='active'",actor,r.get("product_id"))==0) throw new ForbiddenException("not_entitled");
        String raw=security.randomKey("dl"); db.insert("INSERT INTO download_tokens(user_id,release_id,token_hash,expires_at) VALUES(?,?,?,?)",actor,rid,security.sha256(raw),Timestamp.from(Instant.now().plus(10,ChronoUnit.MINUTES))); return ok(Map.of("ok",true,"url","/download/"+raw));
    }

    @GET @Path("/workspaces/{wid}/docs")
    public Response docs(@PathParam("wid") long wid,@CookieParam("NF_SESSION") String session){ forge.requireWorkspace(uid(session),wid); return ok(db.query("SELECT * FROM docs WHERE workspace_id=? ORDER BY updated_at DESC",wid)); }
    @POST @Path("/workspaces/{wid}/docs")
    public Response saveDoc(@PathParam("wid") long wid,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf,Map<String,Object> b){
        unsafe(session,csrf); long actor=uid(session); forge.requireWorkspace(actor,wid,"developer","marketing","admin"); b=body(b); String slug=forge.slug(forge.text(b,"slug").isBlank()?forge.text(b,"title"):forge.text(b,"slug")); Map<String,Object> d=db.one("SELECT id FROM docs WHERE workspace_id=? AND slug=?",wid,slug);
        if(d==null) db.insert("INSERT INTO docs(workspace_id,title,slug,body,published) VALUES(?,?,?,?,?)",wid,forge.text(b,"title"),slug,forge.text(b,"body"),forge.bool(b,"published",false));
        else db.execute("UPDATE docs SET title=?,body=?,published=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",forge.text(b,"title"),forge.text(b,"body"),forge.bool(b,"published",false),d.get("id"));
        forge.audit(wid,actor,"docs.saved",slug,null); return ok(Map.of("ok",true,"slug",slug));
    }

    @GET @Path("/workspaces/{wid}/pages")
    public Response pages(@PathParam("wid") long wid,@CookieParam("NF_SESSION") String session){ forge.requireWorkspace(uid(session),wid); return ok(db.query("SELECT * FROM site_pages WHERE workspace_id=? ORDER BY updated_at DESC",wid)); }
    @POST @Path("/workspaces/{wid}/pages")
    public Response savePage(@PathParam("wid") long wid,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf,Map<String,Object> b){
        unsafe(session,csrf); long actor=uid(session); forge.requireWorkspace(actor,wid,"developer","marketing","admin"); b=body(b); String slug=forge.slug(forge.text(b,"slug").isBlank()?forge.text(b,"title"):forge.text(b,"slug")); Map<String,Object> d=db.one("SELECT id FROM site_pages WHERE workspace_id=? AND slug=?",wid,slug);
        if(d==null) db.insert("INSERT INTO site_pages(workspace_id,title,slug,layout_json,theme,published) VALUES(?,?,?,?,?,?)",wid,forge.text(b,"title"),slug,forge.text(b,"layout_json"),forge.text(b,"theme").isBlank()?"light":forge.text(b,"theme"),forge.bool(b,"published",false));
        else db.execute("UPDATE site_pages SET title=?,layout_json=?,theme=?,published=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",forge.text(b,"title"),forge.text(b,"layout_json"),forge.text(b,"theme").isBlank()?"light":forge.text(b,"theme"),forge.bool(b,"published",false),d.get("id"));
        forge.audit(wid,actor,"page.saved",slug,null); return ok(Map.of("ok",true,"slug",slug));
    }
    @DELETE @Path("/workspaces/{wid}/pages/{pageId}")
    public Response deletePage(@PathParam("wid") long wid,@PathParam("pageId") long pageId,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf){ unsafe(session,csrf); long actor=uid(session); forge.requireWorkspace(actor,wid,"developer","marketing","admin"); db.execute("DELETE FROM site_pages WHERE id=? AND workspace_id=?",pageId,wid); forge.audit(wid,actor,"page.deleted",String.valueOf(pageId),null); return ok(Map.of("ok",true)); }

    @GET @Path("/client/support")
    public Response support(@CookieParam("NF_SESSION") String session){ return ok(db.query("SELECT st.*,p.name product_name,w.name workspace_name FROM support_tickets st LEFT JOIN products p ON p.id=st.product_id LEFT JOIN workspaces w ON w.id=st.workspace_id WHERE st.user_id=? ORDER BY st.created_at DESC",uid(session))); }
    @POST @Path("/client/support")
    public Response createSupport(@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf,Map<String,Object> b){
        unsafe(session,csrf); long actor=uid(session); b=body(b); Long productId=forge.longValue(b,"product_id",0); if(productId==0) productId=null; Long wid=null; if(productId!=null){Map<String,Object> p=db.one("SELECT workspace_id FROM products WHERE id=?",productId); if(p!=null) wid=((Number)p.get("workspace_id")).longValue();}
        long tid=db.insert("INSERT INTO support_tickets(workspace_id,product_id,user_id,subject,priority) VALUES(?,?,?,?,?)",wid,productId,actor,forge.text(b,"subject"),forge.text(b,"priority").isBlank()?"normal":forge.text(b,"priority")); String msg=forge.text(b,"message"); if(!msg.isBlank()) db.insert("INSERT INTO support_messages(ticket_id,user_id,message) VALUES(?,?,?)",tid,actor,msg); return ok(Map.of("ok",true,"ticket_id",tid));
    }
    @GET @Path("/workspaces/{wid}/support")
    public Response workspaceSupport(@PathParam("wid") long wid,@CookieParam("NF_SESSION") String session){ forge.requireWorkspace(uid(session),wid,"support","developer","admin"); return ok(db.query("SELECT st.*,u.email,u.display_name,p.name product_name FROM support_tickets st JOIN users u ON u.id=st.user_id LEFT JOIN products p ON p.id=st.product_id WHERE st.workspace_id=? ORDER BY st.created_at DESC",wid)); }

    @GET @Path("/workspaces/{wid}/integrations")
    public Response integrations(@PathParam("wid") long wid,@CookieParam("NF_SESSION") String session){ forge.requireWorkspace(uid(session),wid,"admin"); return ok(db.query("SELECT id,workspace_id,type,config_json,enabled,updated_at FROM integrations WHERE workspace_id=? ORDER BY type",wid)); }
    @PUT @Path("/workspaces/{wid}/integrations/{type}")
    public Response saveIntegration(@PathParam("wid") long wid,@PathParam("type") String type,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf,Map<String,Object> b){
        unsafe(session,csrf); long actor=uid(session); forge.requireWorkspace(actor,wid,"admin"); b=body(b); Map<String,Object> i=db.one("SELECT id FROM integrations WHERE workspace_id=? AND type=?",wid,type);
        if(i==null) db.insert("INSERT INTO integrations(workspace_id,type,config_json,enabled) VALUES(?,?,?,?)",wid,type,forge.text(b,"config_json"),forge.bool(b,"enabled",true));
        else db.execute("UPDATE integrations SET config_json=?,enabled=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",forge.text(b,"config_json"),forge.bool(b,"enabled",true),i.get("id"));
        forge.audit(wid,actor,"integration.saved",type,null); return ok(Map.of("ok",true));
    }
    @GET @Path("/workspaces/{wid}/infra")
    public Response infra(@PathParam("wid") long wid,@CookieParam("NF_SESSION") String session){ forge.requireWorkspace(uid(session),wid); return ok(db.query("SELECT * FROM infra_nodes WHERE workspace_id=? ORDER BY created_at DESC",wid)); }
    @POST @Path("/workspaces/{wid}/infra")
    public Response addInfra(@PathParam("wid") long wid,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf,Map<String,Object> b){ unsafe(session,csrf); long actor=uid(session); forge.requireWorkspace(actor,wid,"developer","admin"); b=body(b); long id=db.insert("INSERT INTO infra_nodes(workspace_id,name,type,url,status,metadata_json) VALUES(?,?,?,?,?,?)",wid,forge.text(b,"name"),forge.text(b,"type"),forge.text(b,"url"),"unknown",forge.text(b,"metadata_json")); forge.audit(wid,actor,"infra.created",String.valueOf(id),null); return ok(Map.of("ok",true,"id",id)); }
    @GET @Path("/workspaces/{wid}/api-keys")
    public Response apiKeys(@PathParam("wid") long wid,@CookieParam("NF_SESSION") String session){ forge.requireWorkspace(uid(session),wid,"admin"); return ok(db.query("SELECT id,name,prefix,scopes,revoked,created_at FROM api_keys WHERE workspace_id=? ORDER BY created_at DESC",wid)); }
    @POST @Path("/workspaces/{wid}/api-keys")
    public Response createApiKey(@PathParam("wid") long wid,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf,Map<String,Object> b){ unsafe(session,csrf); long actor=uid(session); forge.requireWorkspace(actor,wid,"admin"); b=body(b); String raw=security.randomKey("nfk"),prefix=raw.substring(0,Math.min(14,raw.length())); long id=db.insert("INSERT INTO api_keys(workspace_id,name,key_hash,prefix,scopes) VALUES(?,?,?,?,?)",wid,forge.text(b,"name"),security.sha256(raw),prefix,forge.text(b,"scopes")); forge.audit(wid,actor,"api_key.created",String.valueOf(id),prefix); return ok(Map.of("ok",true,"id",id,"key",raw)); }
    @GET @Path("/workspaces/{wid}/audit")
    public Response audit(@PathParam("wid") long wid,@CookieParam("NF_SESSION") String session){ forge.requireWorkspace(uid(session),wid); return ok(db.query("SELECT ae.*,u.display_name,u.email FROM audit_events ae LEFT JOIN users u ON u.id=ae.user_id WHERE ae.workspace_id=? ORDER BY ae.created_at DESC LIMIT 300",wid)); }

    @POST @Path("/license/validate")
    public Response validateLicense(Map<String,Object> b){
        b=body(b); String forgeKey=forge.text(b,"forge_key"),slug=forge.text(b,"product_slug"),serverId=forge.text(b,"server_id"),keymaster=forge.text(b,"keymaster");
        if(forgeKey.isBlank()||slug.isBlank()||serverId.isBlank()) throw new BadRequestException("missing_fields");
        Map<String,Object> row=db.one("""
          SELECT l.id license_id,l.status license_status,l.server_limit,p.id product_id,p.name,p.slug,p.license_required,p.protection_mode,u.id user_id
          FROM users u JOIN licenses l ON l.user_id=u.id JOIN products p ON p.id=l.product_id
          WHERE u.forge_key=? AND p.slug=? AND p.status='published'
          """,forgeKey,slug);
        if(row==null||!"active".equals(row.get("license_status"))) return Response.status(403).entity(Map.of("ok",false,"error","license_invalid")).build();
        long lid=((Number)row.get("license_id")).longValue();
        Map<String,Object> act=db.one("SELECT id,revoked FROM license_activations WHERE license_id=? AND server_id=?",lid,serverId);
        if(act==null){
            long active=db.count("SELECT COUNT(*) FROM license_activations WHERE license_id=? AND revoked=FALSE",lid);
            if(active>=((Number)row.get("server_limit")).longValue()) return Response.status(403).entity(Map.of("ok",false,"error","server_limit")).build();
            db.insert("INSERT INTO license_activations(license_id,server_id,keymaster) VALUES(?,?,?)",lid,serverId,keymaster);
        }else{
            if(Boolean.TRUE.equals(act.get("revoked"))) return Response.status(403).entity(Map.of("ok",false,"error","activation_revoked")).build();
            db.execute("UPDATE license_activations SET keymaster=?,last_seen_at=CURRENT_TIMESTAMP WHERE id=?",keymaster,act.get("id"));
        }
        return ok(Map.of("ok",true,"product",row.get("name"),"slug",row.get("slug"),"protection_mode",row.get("protection_mode")));
    }

    private void grant(long userId,long productId,String source){
        if(db.count("SELECT COUNT(*) FROM entitlements WHERE user_id=? AND product_id=?",userId,productId)==0) db.insert("INSERT INTO entitlements(user_id,product_id,source,status) VALUES(?,?,?,'active')",userId,productId,source);
        else db.execute("UPDATE entitlements SET status='active',source=? WHERE user_id=? AND product_id=?",source,userId,productId);
        Map<String,Object> p=db.one("SELECT license_required FROM products WHERE id=?",productId);
        if(p!=null&&Boolean.TRUE.equals(p.get("license_required"))&&db.count("SELECT COUNT(*) FROM licenses WHERE user_id=? AND product_id=?",userId,productId)==0)
            db.insert("INSERT INTO licenses(user_id,product_id,license_key,status,server_limit) VALUES(?,?,?,'active',1)",userId,productId,security.randomKey("NFL").toUpperCase(Locale.ROOT));
    }
}
