package com.nordlab.forgeadmin;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminResource {
    @Inject AdminSessionService sessions;
    @ConfigProperty(name="NORD_CORE_INTERNAL_URL",defaultValue="http://127.0.0.1:8088") String core;
    @ConfigProperty(name="NORD_ADMIN_SERVICE_SECRET") String serviceSecret;

    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();

    @POST @Path("/session")
    public Response session(Map<String,Object> b){
        var s=sessions.accept(String.valueOf(b.get("token")));
        return Response.ok(Map.of("ok",true,"email",s.email())).header("Set-Cookie",sessions.cookie(s.token())).build();
    }
    @DELETE @Path("/session")
    public Response logout(@CookieParam("NF_ADMIN_SESSION") String token){ sessions.remove(token); return Response.ok(Map.of("ok",true)).header("Set-Cookie",sessions.clearCookie()).build(); }
    @GET @Path("/me")
    public Response me(@CookieParam("NF_ADMIN_SESSION") String token){ var s=sessions.require(token); return Response.ok(Map.of("ok",true,"email",s.email(),"user_id",s.userId())).build(); }

    @GET @Path("/summary")
    public Response summary(@CookieParam("NF_ADMIN_SESSION") String token){ sessions.require(token); return proxy("GET","/api/v2/internal/admin/summary",null); }
    @GET @Path("/users")
    public Response users(@CookieParam("NF_ADMIN_SESSION") String token){ sessions.require(token); return proxy("GET","/api/v2/internal/admin/users",null); }
    @GET @Path("/workspaces")
    public Response workspaces(@CookieParam("NF_ADMIN_SESSION") String token){ sessions.require(token); return proxy("GET","/api/v2/internal/admin/workspaces",null); }
    @PUT @Path("/maintenance")
    public Response maintenance(@CookieParam("NF_ADMIN_SESSION") String token,Map<String,Object> b){ sessions.require(token); return proxy("PUT","/api/v2/internal/admin/maintenance","{\"enabled\":"+Boolean.parseBoolean(String.valueOf(b.get("enabled")))+"}"); }

    @GET @Path("/runtime")
    public Response runtime(@CookieParam("NF_ADMIN_SESSION") String token){ sessions.require(token); return proxy("GET","/api/v2/internal/admin/runtime",null); }
    @GET @Path("/audit")
    public Response audit(@CookieParam("NF_ADMIN_SESSION") String token){ sessions.require(token); return proxy("GET","/api/v2/internal/admin/audit",null); }
    @PUT @Path("/users/{uid}/status")
    public Response userStatus(@PathParam("uid") long uid,@CookieParam("NF_ADMIN_SESSION") String token,Map<String,Object> b){
        sessions.require(token); String status=String.valueOf(b.getOrDefault("status","active")).replace("\"","");
        return proxy("PUT","/api/v2/internal/admin/users/"+uid+"/status","{\"status\":\""+status+"\"}");
    }
    @PUT @Path("/workspaces/{wid}/status")
    public Response workspaceStatus(@PathParam("wid") long wid,@CookieParam("NF_ADMIN_SESSION") String token,Map<String,Object> b){
        sessions.require(token); String status=String.valueOf(b.getOrDefault("status","active")).replace("\"","");
        return proxy("PUT","/api/v2/internal/admin/workspaces/"+wid+"/status","{\"status\":\""+status+"\"}");
    }

    @GET @Path("/system/update")
    public Response updateStatus(@CookieParam("NF_ADMIN_SESSION") String token){
        sessions.require(token);
        java.nio.file.Path control=java.nio.file.Path.of("/var/lib/nord-forge/control");
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("ok",true);
        out.put("version",readText(java.nio.file.Path.of("/opt/nord-forge/VERSION"),"unknown").trim());
        out.put("queued",Files.exists(control.resolve("update.request")));
        out.put("status",parseStatus(control.resolve("update.status")));
        out.put("backups",listBackups());
        return Response.ok(out).build();
    }

    @POST @Path("/system/update")
    public Response requestUpdate(@CookieParam("NF_ADMIN_SESSION") String token){
        sessions.require(token);
        try{
            java.nio.file.Path control=java.nio.file.Path.of("/var/lib/nord-forge/control");
            Files.createDirectories(control);
            java.nio.file.Path request=control.resolve("update.request");
            if(Files.exists(request)) return Response.status(409).entity(Map.of("ok",false,"error","update_already_queued")).build();
            Map<String,String> current=parseStatus(control.resolve("update.status"));
            if("running".equals(current.get("state"))) return Response.status(409).entity(Map.of("ok",false,"error","update_running")).build();
            Files.writeString(request,"requested_at="+Instant.now()+"\nrequested_by="+sessions.require(token).email()+"\n");
            return Response.accepted(Map.of("ok",true,"state","queued")).build();
        }catch(Exception e){
            return Response.serverError().entity(Map.of("ok",false,"error","update_request_failed")).build();
        }
    }

    @GET @Path("/system/update/log")
    public Response updateLog(@CookieParam("NF_ADMIN_SESSION") String token){
        sessions.require(token);
        java.nio.file.Path log=java.nio.file.Path.of("/var/lib/nord-forge/control/update.log");
        if(!Files.exists(log)) return Response.ok(Map.of("ok",true,"log","")).build();
        try{
            List<String> lines=Files.readAllLines(log,StandardCharsets.UTF_8);
            int from=Math.max(0,lines.size()-250);
            return Response.ok(Map.of("ok",true,"log",String.join("\n",lines.subList(from,lines.size())))).build();
        }catch(Exception e){
            return Response.serverError().entity(Map.of("ok",false,"error","update_log_unavailable")).build();
        }
    }

    private List<Map<String,Object>> listBackups(){
        java.nio.file.Path dir=java.nio.file.Path.of("/var/lib/nord-forge/backups");
        if(!Files.isDirectory(dir)) return List.of();
        try(var stream=Files.list(dir)){
            return stream.filter(Files::isRegularFile).sorted(Comparator.comparingLong(this::modified).reversed()).limit(30).map(p->{
                Map<String,Object> item=new LinkedHashMap<>();
                item.put("name",p.getFileName().toString());
                try{item.put("size",Files.size(p));item.put("modified_at",Files.getLastModifiedTime(p).toInstant().toString());}catch(Exception ignored){item.put("size",0L);}
                return item;
            }).toList();
        }catch(Exception e){return List.of();}
    }

    private long modified(java.nio.file.Path p){
        try{return Files.getLastModifiedTime(p).toMillis();}catch(Exception e){return 0L;}
    }

    private Map<String,String> parseStatus(java.nio.file.Path path){
        Map<String,String> out=new LinkedHashMap<>();
        if(!Files.exists(path)){out.put("state","idle");return out;}
        try{
            for(String line:Files.readAllLines(path,StandardCharsets.UTF_8)){
                int i=line.indexOf('=');if(i>0)out.put(line.substring(0,i).trim(),line.substring(i+1).trim());
            }
        }catch(Exception e){out.put("state","unknown");}
        return out;
    }

    private String readText(java.nio.file.Path path,String fallback){
        try{return Files.readString(path,StandardCharsets.UTF_8);}catch(Exception e){return fallback;}
    }

    private Response proxy(String method,String path,String body){
        try{
            HttpRequest.Builder rb=HttpRequest.newBuilder(URI.create(core+path)).timeout(Duration.ofSeconds(8)).header("X-Nord-Admin-Service",serviceSecret).header("Accept","application/json");
            if("PUT".equals(method)) rb.header("Content-Type","application/json").PUT(HttpRequest.BodyPublishers.ofString(body==null?"{}":body));
            else rb.GET();
            HttpResponse<String> r=http.send(rb.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return Response.status(r.statusCode()).type(MediaType.APPLICATION_JSON).entity(r.body()).build();
        }catch(Exception e){ return Response.status(502).entity(Map.of("ok",false,"error","core_unavailable")).build(); }
    }
}
