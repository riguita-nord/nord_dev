package com.nordlab.forge;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;

import java.util.*;

@ApplicationScoped
public class ForgeService {
    @Inject Database db;

    public long userId(Map<String,Object> user){ return ((Number)user.get("id")).longValue(); }
    public boolean isOwner(Map<String,Object> user){ return Boolean.TRUE.equals(user.get("platform_owner")); }

    public Map<String,Object> workspace(long wid){
        Map<String,Object> w=db.one("SELECT * FROM workspaces WHERE id=?",wid);
        if(w==null) throw new NotFoundException("workspace_not_found");
        return w;
    }

    public String role(long uid,long wid){
        Map<String,Object> w=workspace(wid);
        if(((Number)w.get("owner_id")).longValue()==uid) return "owner";
        Map<String,Object> m=db.one("SELECT role FROM workspace_members WHERE workspace_id=? AND user_id=? AND status='active'",wid,uid);
        return m==null?null:String.valueOf(m.get("role"));
    }

    public void requireWorkspace(long uid,long wid,String...allowed){
        String role=role(uid,wid);
        if(role==null) throw new ForbiddenException("workspace_access_denied");
        if(allowed.length==0||"owner".equals(role)) return;
        for(String a:allowed) if(a.equals(role)) return;
        throw new ForbiddenException("workspace_permission_denied");
    }

    public void audit(Long wid,Long uid,String action,String target,String details){
        db.insert("INSERT INTO audit_events(workspace_id,user_id,action,target,details) VALUES(?,?,?,?,?)",wid,uid,action,target,details);
    }

    public String slug(String input){
        String s=input==null?"":input.toLowerCase(Locale.ROOT).trim().replaceAll("[^a-z0-9]+","-").replaceAll("(^-|-$)","");
        return s.isBlank()?"item-"+System.currentTimeMillis():s;
    }

    public String uniqueWorkspaceSlug(String name){
        String base=slug(name), candidate=base; int n=2;
        while(db.count("SELECT COUNT(*) FROM workspaces WHERE slug=?",candidate)>0) candidate=base+"-"+n++;
        return candidate;
    }

    public String text(Map<String,Object> body,String key){ Object v=body.get(key); return v==null?"":String.valueOf(v).trim(); }
    public long longValue(Map<String,Object> body,String key,long fallback){ try{return Long.parseLong(String.valueOf(body.get(key)));}catch(Exception e){return fallback;} }
    public boolean bool(Map<String,Object> body,String key,boolean fallback){ Object v=body.get(key); return v==null?fallback:Boolean.parseBoolean(String.valueOf(v)); }
}
