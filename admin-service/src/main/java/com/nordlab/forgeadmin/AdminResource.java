package com.nordlab.forgeadmin;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

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
