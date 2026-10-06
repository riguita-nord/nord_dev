package com.nordlab.forge;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipInputStream;

@ApplicationScoped
public class StorageService {
    @ConfigProperty(name="NORD_FORGE_DATA_DIR",defaultValue="/tmp/nord-forge") String root;

    public String saveRelease(long workspaceId,long productId,String version,String fileName,String fileBase64){
        if(fileBase64==null||fileBase64.isBlank()) throw validation("release_file_required");
        byte[] data;
        try{ data=Base64.getDecoder().decode(fileBase64); }catch(Exception e){ throw validation("invalid_base64"); }
        if(data.length>512L*1024L*1024L) throw validation("release_too_large_512mb");
        if(fileName==null||!fileName.toLowerCase(Locale.ROOT).endsWith(".zip")) throw validation("zip_required");
        try(ZipInputStream zin=new ZipInputStream(new ByteArrayInputStream(data))){
            if(zin.getNextEntry()==null) throw validation("invalid_zip");
        }catch(IOException e){throw validation("invalid_zip");}
        try{
            Path dir=Path.of(root,"storage","releases",String.valueOf(workspaceId),String.valueOf(productId),safe(version));
            Files.createDirectories(dir);
            Path out=dir.resolve(safe(fileName));
            Files.write(out,data,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
            return out.toAbsolutePath().toString();
        }catch(IOException e){throw new IllegalStateException("release_storage_failed",e);}
    }

    private WebApplicationException validation(String code){
        return new WebApplicationException(
            Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(Map.of("ok",false,"error",code,"message",code))
                .build()
        );
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

    public Map<String,Object> reconcile(Database db){
        Path rootPath=Path.of(root).toAbsolutePath().normalize();
        Path releaseRoot=rootPath.resolve("storage").resolve("releases").normalize();
        Path moduleRoot=rootPath.resolve("storage").resolve("protection-modules").normalize();

        int missingReleaseRows=0;
        int missingModuleRows=0;
        int orphanReleaseFiles=0;
        int orphanModuleFiles=0;

        List<Map<String,Object>> releases=db.query("SELECT id,product_id,storage_path FROM releases");
        Set<Path> referencedReleaseFiles=new HashSet<>();
        for(Map<String,Object> r:releases){
            long rid=((Number)r.get("id")).longValue();
            Object raw=r.get("storage_path");
            Path p=raw==null?null:Path.of(String.valueOf(raw)).toAbsolutePath().normalize();
            if(p!=null && p.startsWith(rootPath) && Files.isRegularFile(p)){
                referencedReleaseFiles.add(p);
                continue;
            }

            db.execute("DELETE FROM download_tokens WHERE release_id=?",rid);
            db.execute("DELETE FROM protection_sessions WHERE build_id IN(SELECT build_id FROM protection_builds WHERE release_id=?)",rid);
            db.execute("DELETE FROM protection_modules WHERE build_id IN(SELECT build_id FROM protection_builds WHERE release_id=?)",rid);
            db.execute("DELETE FROM protection_installations WHERE release_id=?",rid);
            db.execute("DELETE FROM protection_builds WHERE release_id=?",rid);
            db.execute("DELETE FROM releases WHERE id=?",rid);
            missingReleaseRows++;
        }

        List<Map<String,Object>> modules=db.query("SELECT id,storage_path FROM protection_modules");
        Set<Path> referencedModuleFiles=new HashSet<>();
        for(Map<String,Object> m:modules){
            long id=((Number)m.get("id")).longValue();
            Object raw=m.get("storage_path");
            Path p=raw==null?null:Path.of(String.valueOf(raw)).toAbsolutePath().normalize();
            if(p!=null && p.startsWith(rootPath) && Files.isRegularFile(p)){
                referencedModuleFiles.add(p);
                continue;
            }
            db.execute("DELETE FROM protection_modules WHERE id=?",id);
            missingModuleRows++;
        }

        orphanReleaseFiles=deleteUnreferencedFiles(releaseRoot,referencedReleaseFiles);
        orphanModuleFiles=deleteUnreferencedFiles(moduleRoot,referencedModuleFiles);

        int demoted=db.execute("""
          UPDATE products
          SET status='draft'
          WHERE status='published'
            AND NOT EXISTS(
              SELECT 1 FROM releases r
              WHERE r.product_id=products.id AND r.published=TRUE
            )
          """);

        deleteEmptyTreeDirectories(releaseRoot);
        deleteEmptyTreeDirectories(moduleRoot);

        Map<String,Object> out=new LinkedHashMap<>();
        out.put("ok",true);
        out.put("missing_release_rows_removed",missingReleaseRows);
        out.put("missing_module_rows_removed",missingModuleRows);
        out.put("orphan_release_files_removed",orphanReleaseFiles);
        out.put("orphan_module_files_removed",orphanModuleFiles);
        out.put("products_demoted_to_draft",demoted);
        out.put("total_removed",missingReleaseRows+missingModuleRows+orphanReleaseFiles+orphanModuleFiles);
        return out;
    }

    private int deleteUnreferencedFiles(Path base,Set<Path> referenced){
        if(base==null||!Files.exists(base)) return 0;
        final int[] removed={0};
        try(var walk=Files.walk(base)){
            walk.filter(Files::isRegularFile).forEach(p->{
                Path normalized=p.toAbsolutePath().normalize();
                if(!referenced.contains(normalized)){
                    try{
                        if(Files.deleteIfExists(normalized)) removed[0]++;
                    }catch(IOException e){
                        throw new UncheckedIOException(e);
                    }
                }
            });
        }catch(IOException|UncheckedIOException e){
            throw new IllegalStateException("storage_reconcile_failed",e);
        }
        return removed[0];
    }

    private void deleteEmptyTreeDirectories(Path base){
        if(base==null||!Files.exists(base)) return;
        try(var walk=Files.walk(base)){
            walk.filter(Files::isDirectory)
                .sorted(Comparator.reverseOrder())
                .filter(p->!p.equals(base))
                .forEach(p->{
                    try(DirectoryStream<Path> entries=Files.newDirectoryStream(p)){
                        if(!entries.iterator().hasNext()) Files.deleteIfExists(p);
                    }catch(IOException ignored){}
                });
        }catch(IOException e){
            throw new IllegalStateException("storage_reconcile_failed",e);
        }
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
