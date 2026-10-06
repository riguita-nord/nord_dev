package com.nordlab.forge;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.util.regex.*;

@ApplicationScoped
public class StorageService {
    @ConfigProperty(name="NORD_FORGE_DATA_DIR",defaultValue="/tmp/nord-forge") String root;

    public Map<String,Object> saveReleaseUpload(long workspaceId,String fileName,InputStream input){
        if(input==null) throw validation("release_file_required");
        if(fileName==null||!fileName.toLowerCase(Locale.ROOT).endsWith(".zip")) throw validation("zip_required");

        String token=UUID.randomUUID().toString();
        Path uploadDir=Path.of(root,"storage","tmp","release-uploads",String.valueOf(workspaceId)).toAbsolutePath().normalize();
        Path rootPath=Path.of(root).toAbsolutePath().normalize();
        if(!uploadDir.startsWith(rootPath)) throw new IllegalArgumentException("storage_path_outside_root");

        Path temp=uploadDir.resolve(token+".upload").normalize();
        long total=0;
        try{
            Files.createDirectories(uploadDir);
            try(OutputStream out=Files.newOutputStream(temp,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
                byte[] buffer=new byte[1024*1024];
                int read;
                while((read=input.read(buffer))!=-1){
                    total+=read;
                    if(total>512L*1024L*1024L){
                        try{Files.deleteIfExists(temp);}catch(IOException ignored){}
                        throw validation("release_too_large_512mb");
                    }
                    out.write(buffer,0,read);
                }
            }
            if(total==0){
                Files.deleteIfExists(temp);
                throw validation("release_file_required");
            }
            validateZipPath(temp);
            return Map.of(
                "ok",true,
                "upload_token",token,
                "file_name",fileName,
                "size",total
            );
        }catch(WebApplicationException e){
            try{Files.deleteIfExists(temp);}catch(IOException ignored){}
            throw e;
        }catch(IOException e){
            try{Files.deleteIfExists(temp);}catch(IOException ignored){}
            throw new IllegalStateException("release_upload_failed",e);
        }
    }

    public String claimReleaseUpload(long workspaceId,long productId,String version,String fileName,String token){
        if(token==null||!token.matches("[0-9a-fA-F-]{36}")) throw validation("release_upload_token_invalid");
        if(fileName==null||!fileName.toLowerCase(Locale.ROOT).endsWith(".zip")) throw validation("zip_required");

        Path rootPath=Path.of(root).toAbsolutePath().normalize();
        Path temp=Path.of(root,"storage","tmp","release-uploads",String.valueOf(workspaceId),token+".upload").toAbsolutePath().normalize();
        if(!temp.startsWith(rootPath)||!Files.isRegularFile(temp)) throw validation("release_upload_not_found");

        validateZipPath(temp);
        Path dir=Path.of(root,"storage","releases",String.valueOf(workspaceId),String.valueOf(productId),safe(version)).toAbsolutePath().normalize();
        if(!dir.startsWith(rootPath)) throw new IllegalArgumentException("storage_path_outside_root");
        Path out=dir.resolve(safe(fileName)).normalize();
        if(!out.startsWith(dir)) throw new IllegalArgumentException("storage_path_outside_root");

        try{
            Files.createDirectories(dir);
            try{
                return Files.move(temp,out,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE).toAbsolutePath().toString();
            }catch(AtomicMoveNotSupportedException ignored){
                return Files.move(temp,out,StandardCopyOption.REPLACE_EXISTING).toAbsolutePath().toString();
            }
        }catch(IOException e){
            throw new IllegalStateException("release_storage_failed",e);
        }
    }

    public void discardReleaseUpload(long workspaceId,String token){
        if(token==null||!token.matches("[0-9a-fA-F-]{36}")) return;
        Path rootPath=Path.of(root).toAbsolutePath().normalize();
        Path temp=Path.of(root,"storage","tmp","release-uploads",String.valueOf(workspaceId),token+".upload").toAbsolutePath().normalize();
        if(!temp.startsWith(rootPath)) return;
        try{Files.deleteIfExists(temp);}catch(IOException ignored){}
    }

    private void validateZipPath(Path path){
        try(ZipInputStream zin=new ZipInputStream(Files.newInputStream(path))){
            if(zin.getNextEntry()==null) throw validation("invalid_zip");
        }catch(WebApplicationException e){
            throw e;
        }catch(IOException e){
            throw validation("invalid_zip");
        }
    }

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

    public Map<String,Object> importNuiFromRelease(String zipPath){
        if(zipPath==null||zipPath.isBlank()) return Map.of();
        Path rootPath=Path.of(root).toAbsolutePath().normalize();
        Path archive=Path.of(zipPath).toAbsolutePath().normalize();
        if(!archive.startsWith(rootPath)||!Files.isRegularFile(archive)) return Map.of();

        try(ZipFile zip=new ZipFile(archive.toFile())){
            Map<String,ZipEntry> entries=new LinkedHashMap<>();
            zip.stream()
                .filter(e->!e.isDirectory())
                .forEach(e->entries.put(normalizeZipName(e.getName()).toLowerCase(Locale.ROOT),e));

            ZipEntry manifest=entries.values().stream()
                .filter(e->{
                    String n=normalizeZipName(e.getName()).toLowerCase(Locale.ROOT);
                    return n.endsWith("/fxmanifest.lua")||n.equals("fxmanifest.lua")||n.endsWith("/__resource.lua")||n.equals("__resource.lua");
                })
                .min(Comparator.comparingInt(e->normalizeZipName(e.getName()).length()))
                .orElse(null);

            String htmlPath=null;
            if(manifest!=null){
                String manifestText=readZipText(zip,manifest,2*1024*1024);
                Matcher ui=Pattern.compile("(?im)^\\s*ui_page\\s*[('\\\"]*([^'\\\"\\)\\r\\n]+)").matcher(manifestText);
                if(ui.find()){
                    String ref=ui.group(1).trim();
                    htmlPath=resolveZipReference(parentZipPath(normalizeZipName(manifest.getName())),ref);
                }
            }

            if(htmlPath==null||!entries.containsKey(htmlPath.toLowerCase(Locale.ROOT))){
                htmlPath=entries.values().stream()
                    .map(e->normalizeZipName(e.getName()))
                    .filter(n->{
                        String low=n.toLowerCase(Locale.ROOT);
                        return low.endsWith("/index.html")||low.equals("index.html");
                    })
                    .filter(n->{
                        String low=n.toLowerCase(Locale.ROOT);
                        return low.contains("/html/")||low.startsWith("html/")||
                               low.contains("/web/")||low.startsWith("web/")||
                               low.contains("/ui/")||low.startsWith("ui/")||
                               low.contains("/nui/")||low.startsWith("nui/")||
                               low.contains("/dist/")||low.startsWith("dist/")||
                               !low.contains("/");
                    })
                    .min(Comparator.comparingInt(String::length))
                    .orElse(null);
            }

            if(htmlPath==null) return Map.of();
            ZipEntry htmlEntry=entries.get(htmlPath.toLowerCase(Locale.ROOT));
            if(htmlEntry==null) return Map.of();

            String html=readZipText(zip,htmlEntry,6*1024*1024);
            String htmlBase=parentZipPath(htmlPath);
            StringBuilder css=new StringBuilder();
            StringBuilder js=new StringBuilder();

            // Preserve inline styles from <head> or <body>.
            Pattern inlineStylePattern=Pattern.compile("(?is)<style\\b[^>]*>(.*?)</style>");
            Matcher inlineStyleMatcher=inlineStylePattern.matcher(html);
            StringBuffer cleanedHtml=new StringBuffer();
            while(inlineStyleMatcher.find()){
                css.append("/* inline style */\\n").append(inlineStyleMatcher.group(1)).append("\\n\\n");
                inlineStyleMatcher.appendReplacement(cleanedHtml,"");
            }
            inlineStyleMatcher.appendTail(cleanedHtml);
            html=cleanedHtml.toString();

            // Read stylesheet links regardless of attribute order.
            Pattern linkTagPattern=Pattern.compile("(?is)<link\\b[^>]*>");
            Matcher linkMatcher=linkTagPattern.matcher(html);
            cleanedHtml=new StringBuffer();
            while(linkMatcher.find()){
                String tag=linkMatcher.group();
                String rel=htmlAttribute(tag,"rel");
                String ref=htmlAttribute(tag,"href");
                if(ref!=null && rel!=null && rel.toLowerCase(Locale.ROOT).contains("stylesheet") && isLocalAsset(ref)){
                    String resolved=resolveZipReference(htmlBase,ref);
                    ZipEntry asset=entries.get(resolved.toLowerCase(Locale.ROOT));
                    if(asset!=null){
                        String assetCss=readZipText(zip,asset,12*1024*1024);
                        assetCss=rewriteCssAssets(zip,entries,parentZipPath(resolved),assetCss);
                        css.append("/* ").append(resolved).append(" */\\n")
                           .append(assetCss).append("\\n\\n");
                        linkMatcher.appendReplacement(cleanedHtml,"");
                        continue;
                    }
                }
                linkMatcher.appendReplacement(cleanedHtml,Matcher.quoteReplacement(tag));
            }
            linkMatcher.appendTail(cleanedHtml);
            html=cleanedHtml.toString();

            Pattern scriptPattern=Pattern.compile("(?is)<script\\b([^>]*)src=[\\\"']([^\\\"']+)[\\\"']([^>]*)>\\s*</script>");
            Matcher scriptMatcher=scriptPattern.matcher(html);
            cleanedHtml=new StringBuffer();
            while(scriptMatcher.find()){
                String ref=scriptMatcher.group(2).trim();
                if(isLocalAsset(ref)){
                    String resolved=resolveZipReference(htmlBase,ref);
                    ZipEntry asset=entries.get(resolved.toLowerCase(Locale.ROOT));
                    if(asset!=null){
                        js.append("/* ").append(resolved).append(" */\\n")
                          .append(readZipText(zip,asset,16*1024*1024)).append("\\n\\n");
                        scriptMatcher.appendReplacement(cleanedHtml,"");
                        continue;
                    }
                }
                scriptMatcher.appendReplacement(cleanedHtml,Matcher.quoteReplacement(scriptMatcher.group()));
            }
            scriptMatcher.appendTail(cleanedHtml);
            html=cleanedHtml.toString();

            // Rewrite local HTML media assets to data URLs so srcdoc can render them.
            html=rewriteHtmlAssets(zip,entries,htmlBase,html);

            // Builder stores only the actual page body, not nested html/head wrappers.
            Matcher bodyMatcher=Pattern.compile("(?is)<body\\b[^>]*>(.*?)</body>").matcher(html);
            if(bodyMatcher.find()) html=bodyMatcher.group(1);
            Map<String,Object> out=new LinkedHashMap<>();
            out.put("html",html);
            out.put("css",css.toString());
            out.put("js",js.toString());
            out.put("settings_json","{\"viewport\":\"desktop\",\"background\":\"transparent\"}");
            out.put("source","release");
            out.put("source_entry",htmlPath);
            out.put("detected",true);
            return out;
        }catch(IOException e){
            throw new IllegalStateException("nui_release_import_failed",e);
        }
    }

    private String htmlAttribute(String tag,String name){
        Matcher m=Pattern.compile("(?is)\\b"+Pattern.quote(name)+"\\s*=\\s*([\\\"'])(.*?)\\1").matcher(tag);
        if(m.find()) return m.group(2).trim();
        m=Pattern.compile("(?is)\\b"+Pattern.quote(name)+"\\s*=\\s*([^\\s>]+)").matcher(tag);
        return m.find()?m.group(1).trim():null;
    }

    private String rewriteCssAssets(ZipFile zip,Map<String,ZipEntry> entries,String cssBase,String source)throws IOException{
        Pattern p=Pattern.compile("(?is)url\\(\\s*([\\\"']?)([^\\)\\\"']+)\\1\\s*\\)");
        Matcher m=p.matcher(source);
        StringBuffer out=new StringBuffer();
        while(m.find()){
            String ref=m.group(2).trim();
            if(isLocalAsset(ref)&&!ref.startsWith("#")){
                String cleanRef=ref;
                int q=cleanRef.indexOf('?'); if(q>=0) cleanRef=cleanRef.substring(0,q);
                int hash=cleanRef.indexOf('#'); if(hash>=0) cleanRef=cleanRef.substring(0,hash);
                String resolved=resolveZipReference(cssBase,cleanRef);
                ZipEntry asset=entries.get(resolved.toLowerCase(Locale.ROOT));
                if(asset!=null && asset.getSize()>=0 && asset.getSize()<=8L*1024L*1024L){
                    byte[] bytes=readZipBytes(zip,asset,8*1024*1024);
                    String data="data:"+mimeFor(resolved)+";base64,"+Base64.getEncoder().encodeToString(bytes);
                    m.appendReplacement(out,Matcher.quoteReplacement("url(\""+data+"\")"));
                    continue;
                }
            }
            m.appendReplacement(out,Matcher.quoteReplacement(m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    private String rewriteHtmlAssets(ZipFile zip,Map<String,ZipEntry> entries,String htmlBase,String source)throws IOException{
        Pattern tagPattern=Pattern.compile("(?is)<(img|source|video|audio)\\b[^>]*>");
        Matcher tagMatcher=tagPattern.matcher(source);
        StringBuffer out=new StringBuffer();
        while(tagMatcher.find()){
            String tag=tagMatcher.group();
            String rewritten=tag;
            for(String attr:List.of("src","poster")){
                String ref=htmlAttribute(rewritten,attr);
                if(ref!=null&&isLocalAsset(ref)){
                    String cleanRef=ref;
                    int q=cleanRef.indexOf('?'); if(q>=0) cleanRef=cleanRef.substring(0,q);
                    String resolved=resolveZipReference(htmlBase,cleanRef);
                    ZipEntry asset=entries.get(resolved.toLowerCase(Locale.ROOT));
                    if(asset!=null&&asset.getSize()>=0&&asset.getSize()<=8L*1024L*1024L){
                        String data="data:"+mimeFor(resolved)+";base64,"+Base64.getEncoder().encodeToString(readZipBytes(zip,asset,8*1024*1024));
                        rewritten=rewritten.replace(ref,data);
                    }
                }
            }
            tagMatcher.appendReplacement(out,Matcher.quoteReplacement(rewritten));
        }
        tagMatcher.appendTail(out);
        return out.toString();
    }

    private byte[] readZipBytes(ZipFile zip,ZipEntry entry,int maxBytes)throws IOException{
        if(entry.getSize()>maxBytes) throw new IOException("zip_entry_too_large");
        try(InputStream in=zip.getInputStream(entry); ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buf=new byte[64*1024];
            int total=0,read;
            while((read=in.read(buf))!=-1){
                total+=read;
                if(total>maxBytes) throw new IOException("zip_entry_too_large");
                out.write(buf,0,read);
            }
            return out.toByteArray();
        }
    }

    private String mimeFor(String path){
        String low=path.toLowerCase(Locale.ROOT);
        if(low.endsWith(".png"))return "image/png";
        if(low.endsWith(".jpg")||low.endsWith(".jpeg"))return "image/jpeg";
        if(low.endsWith(".webp"))return "image/webp";
        if(low.endsWith(".gif"))return "image/gif";
        if(low.endsWith(".svg"))return "image/svg+xml";
        if(low.endsWith(".woff2"))return "font/woff2";
        if(low.endsWith(".woff"))return "font/woff";
        if(low.endsWith(".ttf"))return "font/ttf";
        if(low.endsWith(".otf"))return "font/otf";
        if(low.endsWith(".mp4"))return "video/mp4";
        if(low.endsWith(".webm"))return "video/webm";
        if(low.endsWith(".mp3"))return "audio/mpeg";
        if(low.endsWith(".ogg"))return "audio/ogg";
        return "application/octet-stream";
    }

    private String readZipText(ZipFile zip,ZipEntry entry,int maxBytes)throws IOException{
        if(entry.getSize()>maxBytes) throw new IOException("zip_entry_too_large");
        try(InputStream in=zip.getInputStream(entry); ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buf=new byte[64*1024];
            int total=0,read;
            while((read=in.read(buf))!=-1){
                total+=read;
                if(total>maxBytes) throw new IOException("zip_entry_too_large");
                out.write(buf,0,read);
            }
            return out.toString(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private String normalizeZipName(String value){
        return value==null?"":value.replace('\\','/').replaceAll("^\\./+","");
    }

    private String parentZipPath(String value){
        String n=normalizeZipName(value);
        int slash=n.lastIndexOf('/');
        return slash<0?"":n.substring(0,slash+1);
    }

    private String resolveZipReference(String base,String ref){
        String cleaned=ref==null?"":ref.trim().replace('\\','/');
        while(cleaned.startsWith("./")) cleaned=cleaned.substring(2);
        if(cleaned.startsWith("/")) cleaned=cleaned.substring(1);
        Path resolved=Path.of(base==null?"":base).resolve(cleaned).normalize();
        String out=resolved.toString().replace('\\','/');
        while(out.startsWith("../")) out=out.substring(3);
        return normalizeZipName(out);
    }

    private boolean isLocalAsset(String ref){
        if(ref==null||ref.isBlank()) return false;
        String low=ref.trim().toLowerCase(Locale.ROOT);
        return !low.startsWith("http://")&&!low.startsWith("https://")&&!low.startsWith("//")&&!low.startsWith("data:")&&!low.startsWith("nui://")&&!low.startsWith("https://cfx-nui-");
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
        Path uploadRoot=rootPath.resolve("storage").resolve("tmp").resolve("release-uploads").normalize();

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

        int staleUploads=deleteStaleUploads(uploadRoot);
        deleteEmptyTreeDirectories(releaseRoot);
        deleteEmptyTreeDirectories(moduleRoot);
        deleteEmptyTreeDirectories(uploadRoot);

        Map<String,Object> out=new LinkedHashMap<>();
        out.put("ok",true);
        out.put("missing_release_rows_removed",missingReleaseRows);
        out.put("missing_module_rows_removed",missingModuleRows);
        out.put("orphan_release_files_removed",orphanReleaseFiles);
        out.put("orphan_module_files_removed",orphanModuleFiles);
        out.put("products_demoted_to_draft",demoted);
        out.put("stale_release_uploads_removed",staleUploads);
        out.put("total_removed",missingReleaseRows+missingModuleRows+orphanReleaseFiles+orphanModuleFiles+staleUploads);
        return out;
    }

    private int deleteStaleUploads(Path base){
        if(base==null||!Files.exists(base)) return 0;
        final int[] removed={0};
        long cutoff=System.currentTimeMillis()-(24L*60L*60L*1000L);
        try(var walk=Files.walk(base)){
            walk.filter(Files::isRegularFile).forEach(p->{
                try{
                    if(Files.getLastModifiedTime(p).toMillis()<cutoff && Files.deleteIfExists(p)) removed[0]++;
                }catch(IOException e){
                    throw new UncheckedIOException(e);
                }
            });
        }catch(IOException|UncheckedIOException e){
            throw new IllegalStateException("storage_reconcile_failed",e);
        }
        return removed[0];
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
