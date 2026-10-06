package com.nordlab.forge;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.LinkedHashMap;
import java.util.Map;

@Provider
public class ApiExceptionMapper implements ExceptionMapper<WebApplicationException> {
    @Override
    public Response toResponse(WebApplicationException exception) {
        Response original=exception.getResponse();
        int status=original==null?500:original.getStatus();

        String message=null;
        if(original!=null && original.hasEntity()){
            Object entity=original.getEntity();
            if(entity instanceof Map<?,?> map){
                Object m=map.get("message");
                if(m==null) m=map.get("error");
                if(m!=null) message=String.valueOf(m);
            }else if(entity instanceof String s && !s.isBlank()){
                message=s;
            }
        }

        String exceptionMessage=exception.getMessage();
        if((message==null||message.isBlank()) &&
           exceptionMessage!=null &&
           !exceptionMessage.isBlank() &&
           !exceptionMessage.matches("HTTP \\d{3} .*")){
            message=exceptionMessage;
        }

        Throwable cause=exception.getCause();
        while((message==null||message.isBlank()) && cause!=null){
            String causeMessage=cause.getMessage();
            if(causeMessage!=null&&!causeMessage.isBlank()) message=causeMessage;
            cause=cause.getCause();
        }

        if(message==null||message.isBlank()) message="http_"+status;

        Map<String,Object> body=new LinkedHashMap<>();
        body.put("ok",false);
        body.put("status",status);
        body.put("error",message);
        body.put("message",message);

        return Response.status(status)
            .type(MediaType.APPLICATION_JSON_TYPE)
            .entity(body)
            .build();
    }
}
