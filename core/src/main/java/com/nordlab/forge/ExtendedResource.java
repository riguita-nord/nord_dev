package com.nordlab.forge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.Base64;

@Path("/api/v2")
@Produces(MediaType.APPLICATION_JSON)
public class ExtendedResource {
    @Inject Database db;
    @Inject SecurityService security;
    @Inject ForgeService forge;
    @Inject ObjectMapper json;

    @ConfigProperty(name="NORD_DISCORD_CLIENT_ID",defaultValue="") String discordClientId;
    @ConfigProperty(name="NORD_DISCORD_BOT_TOKEN",defaultValue="") String discordBotToken;
    @ConfigProperty(name="NORD_PUBLIC_URL",defaultValue="http://127.0.0.1:8088") String publicUrl;
    @ConfigProperty(name="NORD_ADMIN_SSO_SECRET") String signingSecret;

    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @GET @Path("/workspaces/{wid}/store")
    public Response getStore(@PathParam("wid") long wid,@CookieParam("NF_SESSION") String session){
        forge.requireWorkspace(forge.userId(security.requireUser(session)),wid);
        return Response.ok(db.one("SELECT id,name,slug,store_name,store_description,store_currency,store_theme,status FROM workspaces WHERE id=?",wid)).build();
    }

    @PUT @Path("/workspaces/{wid}/store")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateStore(@PathParam("wid") long wid,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf,Map<String,Object> b){
        security.requireCsrf(session,csrf); long actor=forge.userId(security.requireUser(session)); forge.requireWorkspace(actor,wid,"marketing","admin");
        db.execute("UPDATE workspaces SET store_name=?,store_description=?,store_currency=?,store_theme=? WHERE id=?",
            text(b,"store_name"),text(b,"store_description"),text(b,"store_currency").isBlank()?"EUR":text(b,"store_currency"),text(b,"store_theme").isBlank()?"dark":text(b,"store_theme"),wid);
        forge.audit(wid,actor,"store.updated",String.valueOf(wid),null);
        return Response.ok(Map.of("ok",true)).build();
    }

    @GET @Path("/workspaces/{wid}/tebex")
    public Response tebex(@PathParam("wid") long wid,@CookieParam("NF_SESSION") String session){
        forge.requireWorkspace(forge.userId(security.requireUser(session)),wid,"finance","admin");
        Map<String,Object> i=db.one("SELECT id,config_json,enabled,updated_at FROM integrations WHERE workspace_id=? AND type='tebex'",wid);
        return Response.ok(i==null?Map.of("enabled",false,"config_json","{}"):i).build();
    }

    @PUT @Path("/workspaces/{wid}/tebex")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response saveTebex(@PathParam("wid") long wid,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf,Map<String,Object> b){
        security.requireCsrf(session,csrf); long actor=forge.userId(security.requireUser(session)); forge.requireWorkspace(actor,wid,"finance","admin");
        upsertIntegration(wid,"tebex",text(b,"config_json"),bool(b,"enabled",true));
        forge.audit(wid,actor,"tebex.updated",String.valueOf(wid),null);
        return Response.ok(Map.of("ok",true)).build();
    }

    @POST @Path("/checkout/tebex/start")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response tebexStart(@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf,Map<String,Object> b){
        security.requireCsrf(session,csrf); security.requireUser(session);
        long pid=number(b,"product_id",0); Map<String,Object> p=db.one("SELECT p.*,w.slug store_slug FROM products p JOIN workspaces w ON w.id=p.workspace_id WHERE p.id=? AND p.status='published'",pid);
        if(p==null) throw new NotFoundException("product_not_found");
        Map<String,Object> i=db.one("SELECT config_json,enabled FROM integrations WHERE workspace_id=? AND type='tebex'",p.get("workspace_id"));
        if(i==null||!Boolean.TRUE.equals(i.get("enabled"))) throw new BadRequestException("tebex_not_configured");
        Map<String,Object> cfg=parse(String.valueOf(i.get("config_json"))); String url=String.valueOf(cfg.getOrDefault("store_url","")).trim();
        if(url.isBlank()) throw new BadRequestException("tebex_store_url_missing");
        return Response.ok(Map.of("ok",true,"checkout_url",url,"product_slug",p.get("slug"))).build();
    }

    @POST @Path("/webhooks/tebex/{wid}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response tebexWebhook(@PathParam("wid") long wid,@HeaderParam("X-Signature") String signature,String raw){
        Map<String,Object> i=db.one("SELECT config_json,enabled FROM integrations WHERE workspace_id=? AND type='tebex'",wid);
        if(i==null||!Boolean.TRUE.equals(i.get("enabled"))) throw new NotAuthorizedException("tebex_disabled");
        Map<String,Object> cfg=parse(String.valueOf(i.get("config_json"))); String secret=String.valueOf(cfg.getOrDefault("webhook_secret",""));
        if(secret.isBlank()||signature==null||!MessageDigest.isEqual(hmacHex(secret,raw).getBytes(StandardCharsets.UTF_8),signature.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8))) throw new NotAuthorizedException("invalid_signature");
        Map<String,Object> event=parse(raw); String email=String.valueOf(event.getOrDefault("email","")).toLowerCase(Locale.ROOT); String slug=String.valueOf(event.getOrDefault("product_slug","")); String ref=String.valueOf(event.getOrDefault("transaction_id",""));
        Map<String,Object> u=db.one("SELECT id FROM users WHERE email=?",email); Map<String,Object> p=db.one("SELECT id FROM products WHERE workspace_id=? AND slug=?",wid,slug);
        if(u==null||p==null) return Response.status(202).entity(Map.of("ok",false,"pending",true,"reason","user_or_product_not_found")).build();
        grant(((Number)u.get("id")).longValue(),((Number)p.get("id")).longValue(),"tebex");
        db.insert("INSERT INTO purchase_threads(workspace_id,product_id,buyer_id,status,provider,provider_ref) VALUES(?,?,?,'granted','tebex',?)",wid,p.get("id"),u.get("id"),ref);
        return Response.ok(Map.of("ok",true)).build();
    }

    @GET @Path("/workspaces/{wid}/discord")
    public Response discord(@PathParam("wid") long wid,@CookieParam("NF_SESSION") String session){
        forge.requireWorkspace(forge.userId(security.requireUser(session)),wid,"admin");
        Map<String,Object> i=db.one("SELECT id,config_json,enabled,updated_at FROM integrations WHERE workspace_id=? AND type='discord'",wid);
        return Response.ok(i==null?Map.of("enabled",false,"config_json","{}"):i).build();
    }

    @PUT @Path("/workspaces/{wid}/discord")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response saveDiscord(@PathParam("wid") long wid,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf,Map<String,Object> b){
        security.requireCsrf(session,csrf); long actor=forge.userId(security.requireUser(session)); forge.requireWorkspace(actor,wid,"admin");
        upsertIntegration(wid,"discord",text(b,"config_json"),bool(b,"enabled",true)); forge.audit(wid,actor,"discord.updated",String.valueOf(wid),null); return Response.ok(Map.of("ok",true)).build();
    }

    @GET @Path("/workspaces/{wid}/discord/invite")
    public Response discordInvite(@PathParam("wid") long wid,@CookieParam("NF_SESSION") String session){
        long uid=forge.userId(security.requireUser(session)); forge.requireWorkspace(uid,wid,"admin");
        if(discordClientId.isBlank()) throw new BadRequestException("discord_client_id_missing");
        long exp=Instant.now().plusSeconds(300).getEpochSecond(); String raw=wid+":"+uid+":"+exp; String state=Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8))+"."+security.hmac(signingSecret,raw);
        String callback=publicUrl.replaceAll("/$","")+"/api/v2/discord/callback";
        String url="https://discord.com/oauth2/authorize?client_id="+enc(discordClientId)+"&scope=bot%20applications.commands&permissions=117776&response_type=code&redirect_uri="+enc(callback)+"&state="+enc(state);
        return Response.ok(Map.of("ok",true,"url",url)).build();
    }

    @GET @Path("/discord/callback")
    @Produces(MediaType.TEXT_HTML)
    public Response discordCallback(@QueryParam("state") String state,@QueryParam("guild_id") String guildId){
        verifyState(state);
        String raw=new String(Base64.getUrlDecoder().decode(state.substring(0,state.indexOf('.'))),StandardCharsets.UTF_8); long wid=Long.parseLong(raw.split(":")[0]);
        Map<String,Object> old=db.one("SELECT config_json FROM integrations WHERE workspace_id=? AND type='discord'",wid); Map<String,Object> cfg=old==null?new LinkedHashMap<>():parse(String.valueOf(old.get("config_json"))); if(guildId!=null&&!guildId.isBlank())cfg.put("guild_id",guildId);
        try{upsertIntegration(wid,"discord",json.writeValueAsString(cfg),true);}catch(Exception e){throw new IllegalStateException(e);}
        return Response.ok("<!doctype html><meta charset=\"utf-8\"><title>Nord Forge</title><style>body{font-family:system-ui;background:#080b11;color:#fff;display:grid;place-items:center;height:100vh}div{padding:30px;border:1px solid #273247;border-radius:18px;background:#0d121a}</style><div><h2>Discord connected</h2><p>You can close this window and return to Nord Forge.</p></div>").build();
    }

    @GET @Path("/workspaces/{wid}/discord/resources")
    public Response discordResources(@PathParam("wid") long wid,@CookieParam("NF_SESSION") String session){
        forge.requireWorkspace(forge.userId(security.requireUser(session)),wid,"admin");
        Map<String,Object> i=db.one("SELECT config_json FROM integrations WHERE workspace_id=? AND type='discord'",wid); if(i==null) throw new BadRequestException("discord_not_connected");
        String guild=String.valueOf(parse(String.valueOf(i.get("config_json"))).getOrDefault("guild_id","")); if(guild.isBlank()||discordBotToken.isBlank()) throw new BadRequestException("discord_not_ready");
        try{
            HttpResponse<String> channels=discordGet("/guilds/"+guild+"/channels"); HttpResponse<String> roles=discordGet("/guilds/"+guild+"/roles");
            return Response.ok(Map.of("channels",json.readValue(channels.body(),Object.class),"roles",json.readValue(roles.body(),Object.class))).build();
        }catch(Exception e){return Response.status(502).entity(Map.of("ok",false,"error","discord_unavailable")).build();}
    }

    @POST @Path("/workspaces/{wid}/discord/test")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response discordTest(@PathParam("wid") long wid,@CookieParam("NF_SESSION") String session,@HeaderParam("X-CSRF-Token") String csrf,Map<String,Object> b){
        security.requireCsrf(session,csrf); forge.requireWorkspace(forge.userId(security.requireUser(session)),wid,"admin");
        String channel=text(b,"channel_id"); if(channel.isBlank()||discordBotToken.isBlank()) throw new BadRequestException("discord_channel_missing");
        try{
            HttpRequest req=HttpRequest.newBuilder(URI.create("https://discord.com/api/v10/channels/"+channel+"/messages")).timeout(Duration.ofSeconds(8)).header("Authorization","Bot "+discordBotToken).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"content\":\"Nord Forge V2 integration test successful.\"}")).build();
            HttpResponse<String> r=http.send(req,HttpResponse.BodyHandlers.ofString()); return Response.status(r.statusCode()>=200&&r.statusCode()<300?200:502).entity(Map.of("ok",r.statusCode()>=200&&r.statusCode()<300)).build();
        }catch(Exception e){return Response.status(502).entity(Map.of("ok",false,"error","discord_unavailable")).build();}
    }

    private void verifyState(String state){
        if(state==null||!state.contains(".")) throw new NotAuthorizedException("invalid_state"); String[] p=state.split("\\.",2); String raw=new String(Base64.getUrlDecoder().decode(p[0]),StandardCharsets.UTF_8); if(!MessageDigest.isEqual(security.hmac(signingSecret,raw).getBytes(StandardCharsets.UTF_8),p[1].getBytes(StandardCharsets.UTF_8))) throw new NotAuthorizedException("invalid_state"); String[] s=raw.split(":"); if(s.length!=3||Long.parseLong(s[2])<Instant.now().getEpochSecond()) throw new NotAuthorizedException("expired_state");
    }
    private HttpResponse<String> discordGet(String path)throws Exception{HttpRequest req=HttpRequest.newBuilder(URI.create("https://discord.com/api/v10"+path)).timeout(Duration.ofSeconds(8)).header("Authorization","Bot "+discordBotToken).GET().build();return http.send(req,HttpResponse.BodyHandlers.ofString());}
    private void upsertIntegration(long wid,String type,String cfg,boolean enabled){Map<String,Object> i=db.one("SELECT id FROM integrations WHERE workspace_id=? AND type=?",wid,type);if(i==null)db.insert("INSERT INTO integrations(workspace_id,type,config_json,enabled) VALUES(?,?,?,?)",wid,type,cfg,enabled);else db.execute("UPDATE integrations SET config_json=?,enabled=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",cfg,enabled,i.get("id"));}
    private void grant(long userId,long productId,String source){if(db.count("SELECT COUNT(*) FROM entitlements WHERE user_id=? AND product_id=?",userId,productId)==0)db.insert("INSERT INTO entitlements(user_id,product_id,source,status) VALUES(?,?,?,'active')",userId,productId,source);else db.execute("UPDATE entitlements SET status='active',source=? WHERE user_id=? AND product_id=?",source,userId,productId);Map<String,Object> p=db.one("SELECT license_required FROM products WHERE id=?",productId);if(p!=null&&Boolean.TRUE.equals(p.get("license_required"))&&db.count("SELECT COUNT(*) FROM licenses WHERE user_id=? AND product_id=?",userId,productId)==0)db.insert("INSERT INTO licenses(user_id,product_id,license_key,status,server_limit) VALUES(?,?,?,'active',1)",userId,productId,security.randomKey("NFL").toUpperCase(Locale.ROOT));}
    private Map<String,Object> parse(String value){try{return json.readValue(value==null||value.isBlank()?"{}":value,new TypeReference<Map<String,Object>>(){});}catch(Exception e){return new LinkedHashMap<>();}}
    private String text(Map<String,Object>b,String k){Object v=b==null?null:b.get(k);return v==null?"":String.valueOf(v).trim();}
    private long number(Map<String,Object>b,String k,long f){try{return Long.parseLong(String.valueOf(b.get(k)));}catch(Exception e){return f;}}
    private boolean bool(Map<String,Object>b,String k,boolean f){Object v=b==null?null:b.get(k);return v==null?f:Boolean.parseBoolean(String.valueOf(v));}
    private String enc(String v){return URLEncoder.encode(v,StandardCharsets.UTF_8);}
    private String hmacHex(String secret,String raw){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
