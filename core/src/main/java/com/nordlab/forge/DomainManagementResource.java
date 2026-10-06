package com.nordlab.forge;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.util.*;

@Path("/api/v2")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DomainManagementResource {
    @Inject Database db;
    @Inject SecurityService security;
    @Inject ForgeService forge;
    @Inject StorageService storage;

    private long uid(String session){ return forge.userId(security.requireUser(session)); }
    private void unsafe(String session,String csrf){ security.requireCsrf(session,csrf); }
    private String text(Map<String,Object> b,String k){ Object v=b==null?null:b.get(k); return v==null?"":String.valueOf(v).trim(); }
    private long number(Map<String,Object> b,String k,long f){ try{return Long.parseLong(String.valueOf(b.get(k)));}catch(Exception e){return f;} }

    @GET @Path("/client/products/{pid}")
    public Response customerProduct(@PathParam("pid") long pid,@CookieParam("NF_SESSION") String session){
        long actor=uid(session);
        Map<String,Object> p=db.one("""
          SELECT p.*,w.name workspace_name,w.slug store_slug,e.source,e.status entitlement_status
          FROM entitlements e JOIN products p ON p.id=e.product_id JOIN workspaces w ON w.id=p.workspace_id
          WHERE e.user_id=? AND p.id=? AND e.status='active'
          """,actor,pid);
        if(p==null) throw new NotFoundException("product_not_owned");
        Map<String,Object> out=new LinkedHashMap<>(p);
        out.put("releases",db.query("SELECT id,version,changelog,file_name,published,created_at FROM releases WHERE product_id=? AND published=TRUE ORDER BY created_at DESC",pid));
        out.put("license",db.one("SELECT id,license_key,status,server_limit,created_at FROM licenses WHERE user_id=? AND product_id=?",actor,pid));
        return Response.ok(out).build();
    }

    @GET @Path("/public/products/{pid}")
    public Response publicProduct(@PathParam("pid") long pid){
        Map<String,Object> p=db.one("""
          SELECT p.id,p.name,p.slug,p.description,p.category,p.price_cents,p.currency,p.license_required,p.protection_mode,
                 w.name workspace_name,w.slug store_slug
          FROM products p JOIN workspaces w ON w.id=p.workspace_id
          WHERE p.id=? AND p.status='published' AND w.status='active'
          """,pid);
        if(p==null) throw new NotFoundException("product_not_found");
        Map<String,Object> out=new LinkedHashMap<>(p);
        out.put("releases",db.query("SELECT id,version,changelog,created_at FROM releases WHERE product_id=? AND published=TRUE ORDER BY created_at DESC",pid));
        return Response.ok(out).build();
    }

    @DELETE @Path("/workspaces/{wid}")
    public Response deleteWorkspace(@PathParam("wid") long wid,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf,Map<String,Object> b){
        unsafe(session,csrf); long actor=uid(session);
        Map<String,Object> w=forge.workspace(wid);
        if(((Number)w.get("owner_id")).longValue()!=actor) throw new ForbiddenException("workspace_owner_required");
        String confirmation=text(b,"confirmation");
        if(!String.valueOf(w.get("name")).equals(confirmation)) throw new BadRequestException("workspace_confirmation_mismatch");

        List<Map<String,Object>> products=db.query("SELECT id FROM products WHERE workspace_id=?",wid);
        for(Map<String,Object> p:products){
            long pid=((Number)p.get("id")).longValue();
            for(Map<String,Object> r:db.query("SELECT storage_path FROM releases WHERE product_id=?",pid))
                storage.delete(String.valueOf(r.get("storage_path")));
            for(Map<String,Object> m:db.query("SELECT pm.storage_path FROM protection_modules pm JOIN protection_builds pb ON pb.build_id=pm.build_id WHERE pb.product_id=?",pid))
                storage.delete(String.valueOf(m.get("storage_path")));

            db.execute("DELETE FROM protection_modules WHERE build_id IN(SELECT build_id FROM protection_builds WHERE product_id=?)",pid);
            db.execute("DELETE FROM protection_sessions WHERE build_id IN(SELECT build_id FROM protection_builds WHERE product_id=?)",pid);
            db.execute("DELETE FROM protection_installations WHERE product_id=?",pid);
            db.execute("DELETE FROM protection_revocations WHERE workspace_id=?",wid);
            db.execute("DELETE FROM protection_builds WHERE product_id=?",pid);
            db.execute("DELETE FROM product_protection WHERE product_id=?",pid);
            db.execute("DELETE FROM license_logs WHERE product_id=?",pid);
            db.execute("DELETE FROM license_activations WHERE license_id IN(SELECT id FROM licenses WHERE product_id=?)",pid);
            db.execute("DELETE FROM licenses WHERE product_id=?",pid);
            db.execute("DELETE FROM entitlements WHERE product_id=?",pid);
            db.execute("DELETE FROM releases WHERE product_id=?",pid);
        }

        db.execute("DELETE FROM purchase_messages WHERE thread_id IN(SELECT id FROM purchase_threads WHERE workspace_id=?)",wid);
        db.execute("DELETE FROM purchase_threads WHERE workspace_id=?",wid);
        db.execute("DELETE FROM support_messages WHERE ticket_id IN(SELECT id FROM support_tickets WHERE workspace_id=?)",wid);
        db.execute("DELETE FROM support_tickets WHERE workspace_id=?",wid);
        db.execute("DELETE FROM docs WHERE workspace_id=?",wid);
        db.execute("DELETE FROM site_pages WHERE workspace_id=?",wid);
        db.execute("DELETE FROM integrations WHERE workspace_id=?",wid);
        db.execute("DELETE FROM infra_nodes WHERE workspace_id=?",wid);
        db.execute("DELETE FROM api_keys WHERE workspace_id=?",wid);
        db.execute("DELETE FROM bot_events WHERE workspace_id=?",wid);
        db.execute("DELETE FROM workspace_members WHERE workspace_id=?",wid);
        db.execute("DELETE FROM products WHERE workspace_id=?",wid);
        db.execute("DELETE FROM audit_events WHERE workspace_id=?",wid);
        db.execute("DELETE FROM workspaces WHERE id=?",wid);
        return Response.ok(Map.of("ok",true)).build();
    }

    @POST @Path("/workspaces/{wid}/transfer-owner")
    public Response transferOwner(@PathParam("wid") long wid,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf,Map<String,Object> b){
        unsafe(session,csrf); long actor=uid(session); Map<String,Object> w=forge.workspace(wid);
        if(((Number)w.get("owner_id")).longValue()!=actor) throw new ForbiddenException("workspace_owner_required");
        String email=text(b,"email").toLowerCase(Locale.ROOT); Map<String,Object> target=db.one("SELECT id FROM users WHERE email=? AND status='active'",email);
        if(target==null) throw new NotFoundException("user_not_found"); long targetId=((Number)target.get("id")).longValue();
        if(targetId==actor) throw new BadRequestException("already_owner");
        Map<String,Object> oldMember=db.one("SELECT id FROM workspace_members WHERE workspace_id=? AND user_id=?",wid,actor);
        if(oldMember==null) db.insert("INSERT INTO workspace_members(workspace_id,user_id,role,permissions,status) VALUES(?,?,?,'','active')",wid,actor,"admin");
        else db.execute("UPDATE workspace_members SET role='admin',status='active' WHERE id=?",oldMember.get("id"));
        db.execute("DELETE FROM workspace_members WHERE workspace_id=? AND user_id=?",wid,targetId);
        db.execute("UPDATE workspaces SET owner_id=? WHERE id=?",targetId,wid);
        forge.audit(wid,actor,"workspace.owner_transferred",String.valueOf(targetId),email);
        return Response.ok(Map.of("ok",true,"owner_id",targetId)).build();
    }

    @POST @Path("/releases/{rid}/file")
    public Response replaceReleaseFile(@PathParam("rid") long rid,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf,Map<String,Object> b){
        unsafe(session,csrf); long actor=uid(session);
        Map<String,Object> r=db.one("SELECT r.*,p.workspace_id FROM releases r JOIN products p ON p.id=r.product_id WHERE r.id=?",rid);
        if(r==null) throw new NotFoundException("release_not_found"); long wid=((Number)r.get("workspace_id")).longValue(); forge.requireWorkspace(actor,wid,"developer","admin");
        String fileName=text(b,"file_name"); String path=storage.saveRelease(wid,((Number)r.get("product_id")).longValue(),String.valueOf(r.get("version")),fileName,text(b,"file_base64"));
        storage.delete(String.valueOf(r.get("storage_path")));
        db.execute("UPDATE releases SET file_name=?,storage_path=? WHERE id=?",fileName,path,rid);
        forge.audit(wid,actor,"release.file_replaced",String.valueOf(rid),fileName);
        return Response.ok(Map.of("ok",true)).build();
    }

    @GET @Path("/releases/{rid}/direct-download")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response directDownload(@PathParam("rid") long rid,@CookieParam("NF_SESSION") String session){
        long actor=uid(session); Map<String,Object> r=db.one("SELECT r.*,p.workspace_id FROM releases r JOIN products p ON p.id=r.product_id WHERE r.id=?",rid);
        if(r==null) throw new NotFoundException("release_not_found"); forge.requireWorkspace(actor,((Number)r.get("workspace_id")).longValue(),"developer","admin","tester");
        return Response.ok(storage.read(String.valueOf(r.get("storage_path")))).type("application/zip").header("Content-Disposition","attachment; filename=\""+String.valueOf(r.get("file_name")).replace("\"","")+"\"").build();
    }

    @GET @Path("/workspaces/{wid}/license-logs")
    public Response licenseLogs(@PathParam("wid") long wid,@CookieParam("NF_SESSION") String session){
        forge.requireWorkspace(uid(session),wid,"support","developer","admin");
        return Response.ok(db.query("""
          SELECT ll.*,p.name product_name,u.email user_email
          FROM license_logs ll LEFT JOIN products p ON p.id=ll.product_id LEFT JOIN users u ON u.id=ll.user_id
          WHERE p.workspace_id=? ORDER BY ll.created_at DESC LIMIT 500
          """,wid)).build();
    }

    @GET @Path("/license/keymasters")
    public Response keymasters(@CookieParam("NF_SESSION") String session){
        long actor=uid(session); List<Map<String,Object>> rows=db.query("SELECT id,display_name,hostname,status,first_seen_at,last_seen_at,last_ip FROM user_keymasters WHERE user_id=? ORDER BY status,last_seen_at DESC",actor);
        long active=rows.stream().filter(x->"active".equals(x.get("status"))).count();
        return Response.ok(Map.of("ok",true,"slots_used",active,"slots_max",5,"keymasters",rows)).build();
    }

    @POST @Path("/license/keymasters")
    public Response addKeymaster(@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf,Map<String,Object> b){
        unsafe(session,csrf); long actor=uid(session);
        if(db.count("SELECT COUNT(*) FROM licenses WHERE user_id=? AND status='active'",actor)==0) throw new ForbiddenException("no_active_products_for_keymaster");
        String raw=text(b,"keymaster"); if(raw.isBlank()) raw=text(b,"sv_licenseKey");
        if(!raw.startsWith("cfxk_")||raw.length()<20) throw new BadRequestException("invalid_keymaster_format");
        String hash=security.sha256(raw); Map<String,Object> other=db.one("SELECT id FROM user_keymasters WHERE keymaster_hash=? AND user_id<>? AND status='active'",hash,actor);
        if(other!=null) throw new ClientErrorException("keymaster_already_registered",409);
        long active=db.count("SELECT COUNT(*) FROM user_keymasters WHERE user_id=? AND status='active'",actor);
        Map<String,Object> existing=db.one("SELECT * FROM user_keymasters WHERE user_id=? AND keymaster_hash=?",actor,hash);
        if(existing==null&&active>=5) throw new ForbiddenException("keymaster_limit_reached");
        String display=text(b,"display_name"); if(display.isBlank()) display="Keymaster "+Math.max(1,active+1);
        String hostname=text(b,"hostname");
        if(existing==null) db.insert("INSERT INTO user_keymasters(user_id,keymaster_hash,display_name,hostname,status,last_ip) VALUES(?,?,?,?, 'active','')",actor,hash,display,hostname);
        else db.execute("UPDATE user_keymasters SET status='active',display_name=?,hostname=?,last_seen_at=CURRENT_TIMESTAMP WHERE id=?",display,hostname,existing.get("id"));
        return keymasters(session);
    }

    @POST @Path("/license/keymasters/{kid}/remove")
    public Response removeKeymaster(@PathParam("kid") long kid,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf){
        unsafe(session,csrf); long actor=uid(session);
        int n=db.execute("UPDATE user_keymasters SET status='removed',last_seen_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=?",kid,actor);
        if(n==0) throw new NotFoundException("keymaster_not_found");
        return Response.ok(Map.of("ok",true)).build();
    }

    @POST @Path("/workspaces/{wid}/purchases/{tid}/close")
    public Response closePurchase(@PathParam("wid") long wid,@PathParam("tid") long tid,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf){
        unsafe(session,csrf); long actor=uid(session); forge.requireWorkspace(actor,wid,"support","finance","admin");
        db.execute("UPDATE purchase_threads SET status='closed' WHERE id=? AND workspace_id=?",tid,wid); forge.audit(wid,actor,"purchase.closed",String.valueOf(tid),null); return Response.ok(Map.of("ok",true)).build();
    }

    @DELETE @Path("/workspaces/{wid}/purchases/{tid}")
    public Response deletePurchase(@PathParam("wid") long wid,@PathParam("tid") long tid,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf){
        unsafe(session,csrf); long actor=uid(session); forge.requireWorkspace(actor,wid,"finance","admin");
        db.execute("DELETE FROM purchase_messages WHERE thread_id=?",tid); db.execute("DELETE FROM purchase_threads WHERE id=? AND workspace_id=?",tid,wid); forge.audit(wid,actor,"purchase.deleted",String.valueOf(tid),null); return Response.ok(Map.of("ok",true)).build();
    }

    @GET @Path("/support/{tid}/messages")
    public Response supportMessages(@PathParam("tid") long tid,@CookieParam("NF_SESSION") String session){
        long actor=uid(session); Map<String,Object> t=db.one("SELECT * FROM support_tickets WHERE id=?",tid); if(t==null) throw new NotFoundException("ticket_not_found");
        if(((Number)t.get("user_id")).longValue()!=actor){ Object w=t.get("workspace_id"); if(w==null) throw new ForbiddenException("ticket_access_denied"); forge.requireWorkspace(actor,((Number)w).longValue(),"support","developer","admin"); }
        return Response.ok(db.query("SELECT sm.*,u.display_name,u.email FROM support_messages sm JOIN users u ON u.id=sm.user_id WHERE sm.ticket_id=? ORDER BY sm.created_at",tid)).build();
    }

    @POST @Path("/support/{tid}/messages")
    public Response supportMessage(@PathParam("tid") long tid,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf,Map<String,Object> b){
        unsafe(session,csrf); long actor=uid(session); Map<String,Object> t=db.one("SELECT * FROM support_tickets WHERE id=?",tid); if(t==null) throw new NotFoundException("ticket_not_found");
        if(((Number)t.get("user_id")).longValue()!=actor){ Object w=t.get("workspace_id"); if(w==null) throw new ForbiddenException("ticket_access_denied"); forge.requireWorkspace(actor,((Number)w).longValue(),"support","developer","admin"); }
        String message=text(b,"message"); if(message.isBlank()) throw new BadRequestException("message_required");
        db.insert("INSERT INTO support_messages(ticket_id,user_id,message) VALUES(?,?,?)",tid,actor,message); return Response.ok(Map.of("ok",true)).build();
    }

    @PUT @Path("/workspaces/{wid}/support/{tid}")
    public Response supportStatus(@PathParam("wid") long wid,@PathParam("tid") long tid,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf,Map<String,Object> b){
        unsafe(session,csrf); long actor=uid(session); forge.requireWorkspace(actor,wid,"support","admin");
        String status=text(b,"status"); if(!Set.of("open","pending","closed").contains(status)) throw new BadRequestException("invalid_status");
        db.execute("UPDATE support_tickets SET status=? WHERE id=? AND workspace_id=?",status,tid,wid); forge.audit(wid,actor,"support.status",String.valueOf(tid),status); return Response.ok(Map.of("ok",true)).build();
    }

    @DELETE @Path("/workspaces/{wid}/infra/{nodeId}")
    public Response deleteInfra(@PathParam("wid") long wid,@PathParam("nodeId") long nodeId,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf){
        unsafe(session,csrf); long actor=uid(session); forge.requireWorkspace(actor,wid,"developer","admin"); db.execute("DELETE FROM infra_nodes WHERE id=? AND workspace_id=?",nodeId,wid); forge.audit(wid,actor,"infra.deleted",String.valueOf(nodeId),null); return Response.ok(Map.of("ok",true)).build();
    }

    @POST @Path("/workspaces/{wid}/api-keys/{keyId}/revoke")
    public Response revokeApiKey(@PathParam("wid") long wid,@PathParam("keyId") long keyId,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf){
        unsafe(session,csrf); long actor=uid(session); forge.requireWorkspace(actor,wid,"admin"); db.execute("UPDATE api_keys SET revoked=TRUE WHERE id=? AND workspace_id=?",keyId,wid); forge.audit(wid,actor,"api_key.revoked",String.valueOf(keyId),null); return Response.ok(Map.of("ok",true)).build();
    }
    @PUT @Path("/workspaces/{wid}")
    public Response updateWorkspace(@PathParam("wid") long wid,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf,Map<String,Object> b){
        unsafe(session,csrf); long actor=uid(session); forge.requireWorkspace(actor,wid,"admin");
        Map<String,Object> w=forge.workspace(wid); String name=text(b,"name"); if(name.isBlank()) name=String.valueOf(w.get("name"));
        db.execute("UPDATE workspaces SET name=? WHERE id=?",name,wid); forge.audit(wid,actor,"workspace.updated",String.valueOf(wid),name);
        return Response.ok(forge.workspace(wid)).build();
    }

    @DELETE @Path("/products/{pid}")
    public Response deleteProduct(@PathParam("pid") long pid,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf){
        unsafe(session,csrf);
        long actor=uid(session);
        Map<String,Object> p=db.one("SELECT * FROM products WHERE id=?",pid);
        if(p==null) throw new NotFoundException("product_not_found");

        long wid=((Number)p.get("workspace_id")).longValue();
        forge.requireWorkspace(actor,wid,"developer","admin");

        List<Map<String,Object>> releases=db.query("SELECT id,storage_path FROM releases WHERE product_id=?",pid);
        try{
            db.execute("DELETE FROM download_tokens WHERE release_id IN(SELECT id FROM releases WHERE product_id=?)",pid);

            db.execute("DELETE FROM protection_sessions WHERE build_id IN(SELECT build_id FROM protection_builds WHERE product_id=?)",pid);
            db.execute("DELETE FROM protection_modules WHERE build_id IN(SELECT build_id FROM protection_builds WHERE product_id=?)",pid);
            db.execute("DELETE FROM protection_installations WHERE product_id=?",pid);
            db.execute("DELETE FROM protection_builds WHERE product_id=?",pid);
            db.execute("DELETE FROM product_protection WHERE product_id=?",pid);

            db.execute("DELETE FROM license_activations WHERE license_id IN(SELECT id FROM licenses WHERE product_id=?)",pid);
            db.execute("DELETE FROM license_logs WHERE product_id=?",pid);
            db.execute("DELETE FROM licenses WHERE product_id=?",pid);
            db.execute("DELETE FROM entitlements WHERE product_id=?",pid);

            db.execute("DELETE FROM purchase_messages WHERE thread_id IN(SELECT id FROM purchase_threads WHERE product_id=?)",pid);
            db.execute("DELETE FROM purchase_threads WHERE product_id=?",pid);
            db.execute("UPDATE support_tickets SET product_id=NULL WHERE product_id=?",pid);

            db.execute("DELETE FROM releases WHERE product_id=?",pid);
            int deleted=db.execute("DELETE FROM products WHERE id=?",pid);
            if(deleted==0) throw new IllegalStateException("product_delete_failed");

            for(Map<String,Object> r:releases){
                Object storagePath=r.get("storage_path");
                if(storagePath!=null) storage.delete(String.valueOf(storagePath));
            }

            forge.audit(wid,actor,"product.deleted",String.valueOf(pid),String.valueOf(p.get("name")));
            return Response.ok(Map.of("ok",true,"deleted_product_id",pid)).build();
        }catch(WebApplicationException e){
            throw e;
        }catch(Exception e){
            throw new InternalServerErrorException("product_delete_failed: "+e.getMessage(),e);
        }
    }


}
