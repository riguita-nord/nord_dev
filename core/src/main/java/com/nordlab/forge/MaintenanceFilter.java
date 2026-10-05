package com.nordlab.forge;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

@Provider
@Priority(Priorities.AUTHORIZATION)
public class MaintenanceFilter implements ContainerRequestFilter {
    @Inject Database db;

    @Override
    public void filter(ContainerRequestContext request){
        String path=request.getUriInfo().getPath();
        if(path==null) path="";
        if(path.startsWith("q/health") ||
           path.equals("health") ||
           path.startsWith("api/v2/internal/admin/") ||
           path.equals("api/v2/admin/launch") ||
           path.equals("api/v2/auth/logout")) return;

        Map<String,Object> row=db.one("SELECT setting_value FROM platform_settings WHERE setting_key='maintenance'");
        boolean maintenance=row!=null && Boolean.parseBoolean(String.valueOf(row.get("setting_value")));
        if(!maintenance) return;

        request.abortWith(
            Response.status(503)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .header("Retry-After","300")
                .entity(Map.of(
                    "ok",false,
                    "error","platform_maintenance",
                    "message","Nord Forge is temporarily in maintenance mode."
                ))
                .build()
        );
    }
}
