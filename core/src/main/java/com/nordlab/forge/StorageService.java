package com.nordlab.forge;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.BadRequestException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.*;
import java.nio.file.*;
import java.util.Base64;
import java.util.Comparator;
import java.util.zip.ZipInputStream;

@ApplicationScoped
public class StorageService {
    @ConfigProperty(name="NORD_FORGE_DATA_DIR",defaultValue="/tmp/nord-forge") String root;

    public String saveRelease(long workspaceId,long productId,String version,String fileName,String fileBase64){
        if(fileBase64==null||fileBase64.isBlank()) throw new BadRequestException("release_file_required");
        byte[] data;
        try{ data=Base64.getDecoder().decode(fileBase64); }catch(Exception e){ throw new BadRequestException("invalid_base64"); }
        if(data.length>512L*1024L*1024L) throw new BadRequestException("release_too_large_512mb");
        if(fileName==null||!fileName.toLowerCase().endsWith(".zip")) throw new BadRequestException("zip_required");
        try(ZipInputStream zin=new ZipInputStream(new ByteArrayInputStream(data))){
            if(zin.getNextEntry()==null) throw new BadRequestException("invalid_zip");
        }catch(IOException e){throw new BadRequestException("invalid_zip");}
        try{
            Path dir=Path.of(root,"storage","releases",String.valueOf(workspaceId),String.valueOf(productId),safe(version));
            Files.createDirectories(dir);
            Path out=dir.resolve(safe(fileName));
            Files.write(out,data,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
            return out.toAbsolutePath().toString();
        }catch(IOException e){throw new IllegalStateException("release_storage_failed",e);}
    }

    public byte[] read(String path){
        try{return Files.readAllBytes(Path.of(path));}catch(IOException e){throw new IllegalStateException("release_read_failed",e);}
    }
    public void delete(String path){
        if(path==null||path.isBlank()||"null".equalsIgnoreCase(path)) return;
        try{
            Path target=Path.of(path).toAbsolutePath().normalize();
            Path rootPath=Path.of(root).toAbsolutePath().normalize();
            if(!target.startsWith(rootPath)) throw new IllegalArgumentException("storage_path_outside_root");
            Files.deleteIfExists(target);
            deleteEmptyParents(target.getParent(),rootPath.resolve("storage"));
        }catch(IOException ignored){}
    }

    public void deleteProductStorage(long workspaceId,long productId){
        Path rootPath=Path.of(root).toAbsolutePath().normalize();
        Path productDir=rootPath.resolve("storage").resolve("releases").resolve(String.valueOf(workspaceId)).resolve(String.valueOf(productId)).normalize();
        if(!productDir.startsWith(rootPath)) throw new IllegalArgumentException("storage_path_outside_root");
        deleteTree(productDir);
        deleteEmptyParents(productDir.getParent(),rootPath.resolve("storage"));
    }

    public void deleteTree(String path){
        if(path==null||path.isBlank()||"null".equalsIgnoreCase(path)) return;
        Path rootPath=Path.of(root).toAbsolutePath().normalize();
        Path target=Path.of(path).toAbsolutePath().normalize();
        if(!target.startsWith(rootPath)) throw new IllegalArgumentException("storage_path_outside_root");
        deleteTree(target);
    }

    private void deleteTree(Path target){
        if(target==null||!Files.exists(target)) return;
        try(var walk=Files.walk(target)){
            walk.sorted(Comparator.reverseOrder()).forEach(p->{
                try{Files.deleteIfExists(p);}catch(IOException e){throw new UncheckedIOException(e);}
            });
        }catch(IOException|UncheckedIOException e){
            throw new IllegalStateException("storage_delete_failed",e);
        }
    }

    private void deleteEmptyParents(Path dir,Path stopAt){
        if(dir==null||stopAt==null) return;
        Path stop=stopAt.toAbsolutePath().normalize();
        Path current=dir.toAbsolutePath().normalize();
        while(current.startsWith(stop)&&!current.equals(stop)){
            try(DirectoryStream<Path> entries=Files.newDirectoryStream(current)){
                if(entries.iterator().hasNext()) break;
            }catch(IOException e){break;}
            try{Files.deleteIfExists(current);}catch(IOException e){break;}
            current=current.getParent();
            if(current==null) break;
        }
    }

    private String safe(String v){return (v==null?"file":v).replaceAll("[^A-Za-z0-9._-]","_");}
}
