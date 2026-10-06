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
                List<String> htmlCandidates=entries.values().stream()
                    .map(e->normalizeZipName(e.getName()))
                    .filter(n->n.toLowerCase(Locale.ROOT).endsWith(".html"))
                    .toList();

                htmlPath=htmlCandidates.stream()
                    .sorted(Comparator
                        .comparingInt((String n)->nuiHtmlPriority(n))
                        .thenComparingInt(String::length))
                    .findFirst()
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

            Pattern scriptTagPattern=Pattern.compile("(?is)<script\\b[^>]*>\\s*</script>");
            Matcher scriptMatcher=scriptTagPattern.matcher(html);
            cleanedHtml=new StringBuffer();
            while(scriptMatcher.find()){
                String tag=scriptMatcher.group();
                String ref=htmlAttribute(tag,"src");
                if(ref!=null&&isLocalAsset(ref)){
                    String resolved=resolveZipReference(htmlBase,ref);
                    ZipEntry asset=entries.get(resolved.toLowerCase(Locale.ROOT));
                    if(asset!=null){
                        js.append("/* ").append(resolved).append(" */\\n")
                          .append(readZipText(zip,asset,20*1024*1024)).append("\\n\\n");
                        scriptMatcher.appendReplacement(cleanedHtml,"");
                        continue;
                    }
                }
                scriptMatcher.appendReplacement(cleanedHtml,Matcher.quoteReplacement(tag));
            }
            scriptMatcher.appendTail(cleanedHtml);
            html=cleanedHtml.toString();

            // Rewrite local HTML media assets to data URLs so srcdoc can render them.
            html=rewriteHtmlAssets(zip,entries,htmlBase,html);

            // Builder stores only the actual page body, not nested html/head wrappers.
            Matcher bodyMatcher=Pattern.compile("(?is)<body\\b[^>]*>(.*?)</body>").matcher(html);
            if(bodyMatcher.find()) html=bodyMatcher.group(1);
            List<Map<String,Object>> previewMessages=extractNuiPreviewMessages(zip,entries);

            Map<String,Object> out=new LinkedHashMap<>();
            out.put("html",html);
            out.put("css",css.toString());
            out.put("js",js.toString());
            out.put("preview_messages",previewMessages);
            out.put("settings_json","{\"viewport\":\"desktop\",\"background\":\"transparent\"}");
            out.put("source","release");
            out.put("source_entry",htmlPath);
            out.put("detected",true);
            return out;
        }catch(IOException e){
            throw new IllegalStateException("nui_release_import_failed",e);
        }
    }

    private List<Map<String,Object>> extractNuiPreviewMessages(ZipFile zip,Map<String,ZipEntry> entries)throws IOException{
        List<Map<String,Object>> out=new ArrayList<>();
        int inspected=0;
        for(ZipEntry entry:entries.values()){
            String name=normalizeZipName(entry.getName());
            if(!name.toLowerCase(Locale.ROOT).endsWith(".lua")) continue;
            if(entry.getSize()>2L*1024L*1024L) continue;
            if(inspected++>40) break;

            String lua=readZipText(zip,entry,2*1024*1024);
            int cursor=0;
            while(out.size()<32){
                int call=lua.indexOf("SendNUIMessage",cursor);
                if(call<0) break;
                int open=lua.indexOf('{',call);
                if(open<0){cursor=call+14;continue;}
                String table=extractBalancedLuaTable(lua,open);
                if(table==null){cursor=open+1;continue;}
                cursor=open+table.length();

                try{
                    Object parsed=new LuaTableParser(table).parse();
                    if(parsed instanceof Map<?,?> map && !map.isEmpty()){
                        Map<String,Object> payload=new LinkedHashMap<>();
                        map.forEach((k,v)->payload.put(String.valueOf(k),v));
                        Map<String,Object> item=new LinkedHashMap<>();
                        item.put("id","msg-"+out.size());
                        item.put("label",nuiMessageLabel(payload,out.size()+1));
                        item.put("source",name);
                        item.put("payload",payload);
                        item.put("priority",nuiMessagePriority(payload));
                        out.add(item);
                    }
                }catch(RuntimeException ignored){}
            }
        }

        out.sort((a,b)->Integer.compare(
            ((Number)b.getOrDefault("priority",0)).intValue(),
            ((Number)a.getOrDefault("priority",0)).intValue()
        ));
        return out;
    }

    private String extractBalancedLuaTable(String source,int start){
        int depth=0;
        boolean single=false,doubleQ=false,escape=false;
        for(int i=start;i<source.length();i++){
            char ch=source.charAt(i);
            if(escape){escape=false;continue;}
            if((single||doubleQ)&&ch=='\\'){escape=true;continue;}
            if(!doubleQ&&ch=='\''){single=!single;continue;}
            if(!single&&ch=='"'){doubleQ=!doubleQ;continue;}
            if(single||doubleQ)continue;
            if(ch=='{')depth++;
            else if(ch=='}'){
                depth--;
                if(depth==0)return source.substring(start,i+1);
            }
        }
        return null;
    }

    private String nuiMessageLabel(Map<String,Object> payload,int fallback){
        for(String key:List.of("action","type","event","name","page","view","menu")){
            Object v=payload.get(key);
            if(v!=null&&!String.valueOf(v).isBlank()) return humanizePreviewLabel(String.valueOf(v));
        }
        for(String key:List.of("show","open","visible","display")){
            Object v=payload.get(key);
            if(Boolean.TRUE.equals(v)) return "Open UI "+fallback;
        }
        return "NUI message "+fallback;
    }

    private String humanizePreviewLabel(String value){
        String s=value.replaceAll("([a-z])([A-Z])","$1 $2").replace('_',' ').replace('-',' ').trim();
        if(s.isBlank())return "NUI event";
        return Character.toUpperCase(s.charAt(0))+s.substring(1);
    }

    private int nuiMessagePriority(Map<String,Object> payload){
        String marker="";
        for(String key:List.of("action","type","event","name","page","view","menu")){
            if(payload.get(key)!=null) marker+=" "+String.valueOf(payload.get(key)).toLowerCase(Locale.ROOT);
        }
        int score=0;
        if(marker.matches(".*\\b(open|show|display|visible|start|enable|craft|admin|player)\\b.*"))score+=100;
        for(String key:List.of("show","open","visible","display")){
            if(Boolean.TRUE.equals(payload.get(key)))score+=80;
        }
        if(marker.matches(".*\\b(close|hide|disable)\\b.*"))score-=100;
        return score;
    }

    private static final class LuaTableParser{
        private final String s;
        private int i=0;
        LuaTableParser(String s){this.s=s;}

        Object parse(){
            skip();
            return parseValue();
        }

        private Object parseValue(){
            skip();
            if(i>=s.length())return null;
            char ch=s.charAt(i);
            if(ch=='{')return parseTable();
            if(ch=='\''||ch=='"')return parseString();
            if(ch=='-'||Character.isDigit(ch))return parseNumber();
            String word=parseWord();
            if("true".equals(word))return true;
            if("false".equals(word))return false;
            if("nil".equals(word))return null;
            return word;
        }

        private Object parseTable(){
            expect('{');
            LinkedHashMap<String,Object> map=new LinkedHashMap<>();
            List<Object> array=new ArrayList<>();
            boolean keyed=false;
            int index=1;
            while(true){
                skip();
                if(peek('}')){i++;break;}
                int save=i;
                String key=null;

                if(peek('[')){
                    i++;skip();
                    Object raw=parseValue();
                    skip();expect(']');
                    skip();
                    if(peek('=')){i++;key=String.valueOf(raw);keyed=true;}
                    else{i=save;}
                }else{
                    String candidate=parseIdentifier();
                    if(candidate!=null){
                        skip();
                        if(peek('=')){i++;key=candidate;keyed=true;}
                        else i=save;
                    }
                }

                Object value=parseValue();
                if(key!=null) map.put(key,value);
                else array.add(value);

                skip();
                if(peek(',')||peek(';'))i++;
            }
            if(!keyed)return array;
            for(Object v:array)map.put(String.valueOf(index++),v);
            return map;
        }

        private String parseString(){
            char quote=s.charAt(i++);
            StringBuilder out=new StringBuilder();
            boolean esc=false;
            while(i<s.length()){
                char ch=s.charAt(i++);
                if(esc){
                    switch(ch){
                        case 'n'->out.append('\n');
                        case 'r'->out.append('\r');
                        case 't'->out.append('\t');
                        default->out.append(ch);
                    }
                    esc=false;continue;
                }
                if(ch=='\\'){esc=true;continue;}
                if(ch==quote)break;
                out.append(ch);
            }
            return out.toString();
        }

        private Number parseNumber(){
            int start=i;
            if(peek('-'))i++;
            while(i<s.length()&&(Character.isDigit(s.charAt(i))||s.charAt(i)=='.'))i++;
            String n=s.substring(start,i);
            try{return n.contains(".")?Double.parseDouble(n):Long.parseLong(n);}
            catch(NumberFormatException e){return 0;}
        }

        private String parseWord(){
            String id=parseIdentifier();
            return id==null?"":id;
        }

        private String parseIdentifier(){
            skip();
            if(i>=s.length())return null;
            char first=s.charAt(i);
            if(!(Character.isLetter(first)||first=='_'))return null;
            int start=i++;
            while(i<s.length()){
                char ch=s.charAt(i);
                if(!(Character.isLetterOrDigit(ch)||ch=='_'))break;
                i++;
            }
            return s.substring(start,i);
        }

        private void skip(){
            while(i<s.length()&&Character.isWhitespace(s.charAt(i)))i++;
        }
        private boolean peek(char ch){return i<s.length()&&s.charAt(i)==ch;}
        private void expect(char ch){
            skip();
            if(!peek(ch))throw new IllegalArgumentException("lua_table_parse");
            i++;
        }
    }

    private int nuiHtmlPriority(String name){
        String low=normalizeZipName(name).toLowerCase(Locale.ROOT);
        int score=1000;
        if(low.endsWith("/index.html")||low.equals("index.html")) score-=500;
        if(low.contains("/web/")||low.startsWith("web/")) score-=180;
        if(low.contains("/html/")||low.startsWith("html/")) score-=170;
        if(low.contains("/ui/")||low.startsWith("ui/")) score-=160;
        if(low.contains("/nui/")||low.startsWith("nui/")) score-=150;
        if(low.contains("/dist/")||low.startsWith("dist/")) score-=140;
        if(low.contains("/build/")||low.startsWith("build/")) score-=130;
        if(low.contains("/public/")||low.startsWith("public/")) score-=90;
        if(low.contains("test")||low.contains("demo")||low.contains("storybook")) score+=250;
        return score;
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
