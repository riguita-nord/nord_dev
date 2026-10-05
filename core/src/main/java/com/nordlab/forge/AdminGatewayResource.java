package com.nordlab.forge;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;

@Path("/administration")
@Consumes(MediaType.WILDCARD)
@Produces(MediaType.WILDCARD)
public class AdminGatewayResource {
    @ConfigProperty(name="NORD_ADMIN_PORT",defaultValue="8089") int adminPort;

    private final HttpClient http=HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    @GET
    public Response root(@Context UriInfo uri,@Context HttpHeaders headers){
        String rawPath=uri.getRequestUri().getRawPath();
        if(!rawPath.endsWith("/")){
            UriBuilder b=UriBuilder.fromPath("/administration/");
            String q=uri.getRequestUri().getRawQuery();
            if(q!=null&&!q.isBlank()) b.replaceQuery(q);
            return Response.seeOther(b.build()).build();
        }
        return proxy("GET","",uri,headers,null);
    }

    @GET @Path("{path:.*}")
    public Response get(@PathParam("path") String path,@Context UriInfo uri,@Context HttpHeaders headers){
        return proxy("GET",path,uri,headers,null);
    }

    @POST @Path("{path:.*}")
    public Response post(@PathParam("path") String path,@Context UriInfo uri,@Context HttpHeaders headers,byte[] body){
        return proxy("POST",path,uri,headers,body);
    }

    @PUT @Path("{path:.*}")
    public Response put(@PathParam("path") String path,@Context UriInfo uri,@Context HttpHeaders headers,byte[] body){
        return proxy("PUT",path,uri,headers,body);
    }

    @DELETE @Path("{path:.*}")
    public Response delete(@PathParam("path") String path,@Context UriInfo uri,@Context HttpHeaders headers,byte[] body){
        return proxy("DELETE",path,uri,headers,body);
    }

    private Response proxy(String method,String path,UriInfo uri,HttpHeaders headers,byte[] body){
        try{
            String suffix=path==null||path.isBlank()?"":path;
            String target="http://127.0.0.1:"+adminPort+"/administration/"+suffix;
            String query=uri.getRequestUri().getRawQuery();
            if(query!=null&&!query.isBlank()) target+="?"+query;

            HttpRequest.Builder rb=HttpRequest.newBuilder(URI.create(target)).timeout(Duration.ofSeconds(30));
            copyHeader(headers,rb,"Cookie");
            copyHeader(headers,rb,"Accept");
            copyHeader(headers,rb,"Content-Type");
            rb.header("X-Forwarded-Prefix","/administration");

            HttpRequest.BodyPublisher publisher=HttpRequest.BodyPublishers.ofByteArray(body==null?new byte[0]:body);
            switch(method){
                case "POST" -> rb.POST(publisher);
                case "PUT" -> rb.PUT(publisher);
                case "DELETE" -> rb.method("DELETE",publisher);
                default -> rb.GET();
            }

            HttpResponse<byte[]> r=http.send(rb.build(),HttpResponse.BodyHandlers.ofByteArray());
            Response.ResponseBuilder out=Response.status(r.statusCode()).entity(r.body());
            first(r,"content-type").ifPresent(out::type);
            for(String cookie:r.headers().allValues("set-cookie")) out.header("Set-Cookie",cookie);
            first(r,"location").ifPresent(v->out.header("Location",v));
            first(r,"cache-control").ifPresent(v->out.header("Cache-Control",v));
            return out.build();
        }catch(Exception e){
            return Response.status(502).type(MediaType.APPLICATION_JSON_TYPE)
                .entity("{\"ok\":false,\"error\":\"administration_unavailable\"}")
                .build();
        }
    }

    private void copyHeader(HttpHeaders source,HttpRequest.Builder target,String name){
        List<String> values=source.getRequestHeader(name);
        if(values!=null&&!values.isEmpty()) target.header(name,String.join(",",values));
    }

    private java.util.Optional<String> first(HttpResponse<?> r,String name){
        return r.headers().firstValue(name);
    }
}
