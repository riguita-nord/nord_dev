package com.nordlab.forge;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Map;

@Path("/api/v2/internal/maintenance")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MaintenanceResource {
    @Inject Database db;
    @Inject StorageService storage;
    @ConfigProperty(name="NORD_ADMIN_SERVICE_SECRET") String adminServiceSecret;

    private void internal(String secret){
        if(secret==null||!secret.equals(adminServiceSecret)) throw new NotAuthorizedException("admin_service");
    }

    @POST @Path("/reconcile-storage")
    public Response reconcileStorage(@HeaderParam("X-Nord-Admin-Service") String secret){
        internal(secret);
        Map<String,Object> result=storage.reconcile(db);
        return Response.ok(result).build();
    }
}
