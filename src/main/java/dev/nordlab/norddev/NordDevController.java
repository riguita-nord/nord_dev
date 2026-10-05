package dev.nordlab.norddev;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;

@Controller
class NordDevController {
    private final AccountRepository users; private final LicenseRepository licenses; private final ServiceProjectRepository projects; private final ReleaseRepository releases; private final PasswordEncoder encoder; private final PlatformSettingsRepository settings; private final PlatformSetupService setup;
    NordDevController(AccountRepository users,LicenseRepository licenses,ServiceProjectRepository projects,ReleaseRepository releases,PasswordEncoder encoder,PlatformSettingsRepository settings,PlatformSetupService setup){this.users=users;this.licenses=licenses;this.projects=projects;this.releases=releases;this.encoder=encoder;this.settings=settings;this.setup=setup;}
    @org.springframework.context.annotation.Bean UserDetailsService userDetailsService(){return email->users.findByEmailIgnoreCase(email).map(a->User.withUsername(a.email).password(a.passwordHash).roles(a.role).build()).orElseThrow(()->new UsernameNotFoundException("Account not found"));}
    record SetupOwner(@NotBlank @Email String email,@NotBlank @Size(min=12,max=100) String password,@NotBlank String confirm,@NotBlank @Size(max=48) String platformName){}
    record SetupFinish(@NotBlank @Size(max=48) String name,@NotBlank @Pattern(regexp="https?://[^\\s]+") String publicUrl,@NotBlank String timezone){}
    record NewAccount(@NotBlank @Email String email,@NotBlank @Size(min=12,max=100) String password,@Pattern(regexp="USER|ADMIN") String role){}
    record RegisterForm(@NotBlank @Email String email,@Size(min=12,max=100) String password,String confirm){}
    record NewProject(@NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9 _.-]{0,47}") String name,@NotBlank String template){}
    record NewLicense(@NotBlank String product,@NotBlank @Email String ownerEmail,@Min(1) @Max(100) int activations,@Min(0) @Max(3650) int days){}
    record NewRelease(@NotBlank @Pattern(regexp="[0-9]+\\.[0-9]+\\.[0-9]+") String version,@NotBlank String title,@Size(max=5000) String notes,@Pattern(regexp="stable|beta") String channel){}
    record LicenseCheck(@NotBlank String key,@NotBlank String product,@NotBlank @Size(max=180) String serverId){}
    private static final List<Map<String,String>> TEMPLATES=List.of(
        template("java-cli","Java CLI","Minimal Java 21 command line service","terminal","Java 21 · executable JAR"),
        template("fivem-resource","FiveM Resource","Lua resource scaffold with manifest and server/client entrypoints","gamepad","FiveM · resource structure"),
        template("http-service","HTTP Service","Lightweight Java REST service with health and version endpoints","globe","Java 21 · HTTP API"),
        template("worker","Background Worker","Java worker service with graceful shutdown and structured logs","cpu","Java 21 · worker process"),
        template("discord-bot","Discord Bot Service","Java service scaffold for a Discord bot integration","comments","Java 21 · integration"),
        template("static-ui","NUI Interface","FiveM NUI front-end scaffold with resource manifest","window-maximize","HTML · CSS · JavaScript"));
    private static Map<String,String> template(String id,String name,String description,String icon,String stack){return Map.of("id",id,"name",name,"description",description,"icon",icon,"stack",stack);}
    @ModelAttribute("active") String active(){return "";}
    @GetMapping("/") String root(Authentication auth){PlatformSettings p=settings.findById("global").orElseThrow();return "redirect:"+(!p.setupComplete?"/setup":auth==null?"/login":"/app");}
    @GetMapping("/login") String login(){return "login";}
    @GetMapping("/register") String register(){return "redirect:/login";}
    @PostMapping("/register") String closedRegistration(){return "redirect:/login";}
    @GetMapping("/setup") String setupPage(Authentication auth,Model m,@RequestParam(defaultValue="1") int step){PlatformSettings p=settings.findById("global").orElseThrow();if(p.setupComplete)return "redirect:/login";boolean owner=p.ownerId!=null;if(owner&&(auth==null||!isOwner(auth)))return "redirect:/login";m.addAttribute("step",owner?Math.max(3,Math.min(step,4)):Math.min(Math.max(step,1),2));m.addAttribute("ownerExists",owner);m.addAttribute("platform",p.name);m.addAttribute("javaVersion",Runtime.version().feature());m.addAttribute("ownerForm",new SetupOwner("","","",p.name));m.addAttribute("finishForm",new SetupFinish(p.name,"http://localhost:8080","Europe/Lisbon"));return "setup";}
    @PostMapping("/setup/owner") String createOwner(@Valid @ModelAttribute("ownerForm") SetupOwner form,BindingResult errors,Model m,RedirectAttributes flash){if(!Objects.equals(form.password(),form.confirm()))errors.rejectValue("confirm","mismatch","Passwords do not match.");if(form.email()!=null&&users.findByEmailIgnoreCase(form.email().trim()).isPresent())errors.rejectValue("email","exists","This email is already registered.");if(errors.hasErrors()){return setupPage(null,m,2);}try{setup.createFirstOwner(form.email(),form.password(),form.platformName());flash.addFlashAttribute("notice","Platform Owner created. Sign in to finish setup.");return "redirect:/login";}catch(IllegalStateException ex){return "redirect:/setup";}}
    @PostMapping("/setup/finish") String finishSetup(@Valid @ModelAttribute("finishForm") SetupFinish form,BindingResult errors,Authentication auth,Model m,RedirectAttributes flash){if(errors.hasErrors()||!ZoneId.getAvailableZoneIds().contains(form.timezone())){errors.reject("setup.invalid","Check the public URL and time zone.");return setupPage(auth,m,3);}setup.finish(auth.getName(),form.name(),form.publicUrl(),form.timezone());flash.addFlashAttribute("notice","Platform setup complete.");return "redirect:/setup?step=4";}
    @GetMapping("/app") String home(Authentication auth,Model m){PlatformSettings p=settings.findById("global").orElseThrow();if(!p.setupComplete)return "redirect:/setup?step=3";m.addAttribute("platformName",p.name);m.addAttribute("active","overview");m.addAttribute("email",auth.getName());m.addAttribute("projectCount",projects.findByOwnerEmailOrderByCreatedAtDesc(auth.getName()).size());m.addAttribute("licenses",licenses.findAllByOrderByCreatedAtDesc().stream().filter(l->l.ownerEmail.equalsIgnoreCase(auth.getName())).count());m.addAttribute("templates",TEMPLATES);m.addAttribute("releases",releases.findAllByOrderByCreatedAtDesc().stream().filter(r->"PUBLISHED".equals(r.status)).limit(3).toList());return "overview";}
    @GetMapping("/services") String serviceList(Authentication auth,Model m,@RequestParam(defaultValue="java-cli") String template){m.addAttribute("active","services");m.addAttribute("projects",projects.findByOwnerEmailOrderByCreatedAtDesc(auth.getName()));m.addAttribute("templates",TEMPLATES);m.addAttribute("form",new NewProject("",TEMPLATES.stream().anyMatch(t->t.get("id").equals(template))?template:"java-cli"));return "services";}
    @PostMapping("/services") String createService(@Valid @ModelAttribute NewProject form,BindingResult errors,Authentication auth,RedirectAttributes flash){if(errors.hasErrors()||TEMPLATES.stream().noneMatch(t->t.get("id").equals(form.template()))){flash.addFlashAttribute("error","Choose a valid service name and model.");return "redirect:/services";}projects.save(new ServiceProject(form.name().trim(),form.template(),auth.getName()));flash.addFlashAttribute("notice","Service workspace created.");return "redirect:/services";}
    @GetMapping("/services/{id}/download") void downloadService(@PathVariable Long id,Authentication auth,HttpServletResponse response)throws IOException{
        ServiceProject p=projects.findById(id).filter(x->x.ownerEmail.equals(auth.getName())).orElseThrow(); response.setContentType("application/zip");response.setHeader("Content-Disposition","attachment; filename=\""+safe(p.name)+".zip\"");
        try(ZipOutputStream zip=new ZipOutputStream(response.getOutputStream())){Map<String,String> files=serviceFiles(p);for(var f:files.entrySet()){zip.putNextEntry(new ZipEntry(safe(p.name)+"/"+f.getKey()));zip.write(f.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}}
    }
    private Map<String,String> serviceFiles(ServiceProject p){
        String id=safe(p.name), pkg="dev.nordlab."+id.replace('-','_');
        Map<String,String> f=new LinkedHashMap<>();
        f.put("README.md","# "+p.name+"\n\nGenerated from Nord Dev template `"+p.template+"`.\n\nBuild: `mvn package`\n");
        f.put(".gitignore","target/\n*.log\n.env\n");
        if(p.template.equals("fivem-resource")){
            f.put("fxmanifest.lua","fx_version 'cerulean'\ngame 'gta5'\nauthor 'Nord-Lab'\ndescription '"+p.name+"'\nversion '1.0.0'\n\nshared_script 'config.lua'\nclient_script 'client.lua'\nserver_script 'server.lua'\n");
            f.put("config.lua","Config = {}\nConfig.Debug = false\n");
            f.put("client.lua","RegisterCommand('"+id+"', function()\n    print('"+p.name+" client ready')\nend, false)\n");
            f.put("server.lua","print('"+p.name+" server ready')\n");return f;
        }
        if(p.template.equals("static-ui")){
            f.put("fxmanifest.lua","fx_version 'cerulean'\ngame 'gta5'\nui_page 'html/index.html'\nfiles { 'html/index.html', 'html/style.css', 'html/app.js' }\nclient_script 'client.lua'\n");
            f.put("client.lua","RegisterCommand('"+id+"', function()\n    SetNuiFocus(true, true)\n    SendNUIMessage({ action = 'open' })\nend, false)\n\nRegisterNUICallback('close', function(_, cb)\n    SetNuiFocus(false, false)\n    cb({ ok = true })\nend)\n");
            f.put("html/index.html","<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><link rel=\"stylesheet\" href=\"style.css\"><title>"+p.name+"</title></head><body><main><span class=\"tag\">NORD RESOURCE</span><h1>"+p.name+"</h1><p>Your NUI workspace is ready.</p><button id=\"close\">Close</button></main><script src=\"app.js\"></script></body></html>\n");
            f.put("html/style.css","*{box-sizing:border-box}body{display:none;margin:0;background:#101318;color:#edf0f5;font:16px Arial,sans-serif;place-items:center;height:100vh}.visible{display:grid}main{width:min(520px,90vw);padding:32px;background:#191e26;border:1px solid #303744;border-radius:12px}.tag{color:#b6f26b;font-size:11px;letter-spacing:1px}button{background:#b6f26b;border:0;border-radius:6px;padding:10px 16px;cursor:pointer}\n");
            f.put("html/app.js","window.addEventListener('message',event=>{if(event.data.action==='open')document.body.classList.add('visible')});\ndocument.querySelector('#close').addEventListener('click',()=>fetch(`https://${GetParentResourceName()}/close`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({})}));\n");return f;
        }
        f.put("pom.xml","<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion><groupId>"+pkg+"</groupId><artifactId>"+id+"</artifactId><version>1.0.0</version><properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties><build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version><configuration><release>21</release></configuration></plugin></plugins></build></project>\n");
        String body;
        if(p.template.equals("http-service")) body="""
            var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(8080), 0);
            server.createContext("/health", exchange -> {
                byte[] response = "{\\"status\\":\\"ok\\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
            System.out.println("Health endpoint on :8080/health");
            """;
        else if(p.template.equals("worker")) body="var running = new java.util.concurrent.CountDownLatch(1);\n        Runtime.getRuntime().addShutdownHook(new Thread(running::countDown));\n        System.out.println(\""+p.name+" worker started; press Ctrl+C to stop\"); running.await();";
        else if(p.template.equals("discord-bot")) body="System.out.println(\""+p.name+" integration scaffold ready\");\n        System.out.println(\"Add your Discord gateway adapter and keep its token in an environment variable.\");";
        else body="System.out.println(\""+p.name+" service ready\");";
        f.put("src/main/java/"+pkg.replace('.','/')+"/Main.java","package "+pkg+";\n\npublic final class Main {\n    public static void main(String[] args) throws Exception {\n        "+body+"\n    }\n}\n");
        return f;
    }
    @GetMapping("/licenses") String licensePage(Authentication auth,Model m){m.addAttribute("active","licenses");m.addAttribute("licenses",licenses.findAllByOrderByCreatedAtDesc().stream().filter(l->l.ownerEmail.equalsIgnoreCase(auth.getName())||isAdmin(auth)).toList());return "licenses";}
    @PostMapping("/licenses/redeem") String redeem(@RequestParam String key,Authentication auth,RedirectAttributes flash){String hash=digest(key.trim());License l=licenses.findAll().stream().filter(x->MessageDigest.isEqual(x.keyHash.getBytes(StandardCharsets.UTF_8),hash.getBytes(StandardCharsets.UTF_8))).findFirst().orElse(null);if(l==null){flash.addFlashAttribute("error","That license key could not be verified.");return "redirect:/licenses";}if(!l.ownerEmail.equalsIgnoreCase(auth.getName())){flash.addFlashAttribute("error","This license belongs to a different account.");return "redirect:/licenses";}flash.addFlashAttribute("notice","License "+l.product+" linked to your Nord ID.");return "redirect:/licenses";}
    @PostMapping("/api/licenses/verify") @ResponseBody ResponseEntity<Map<String,Object>> verify(@Valid @RequestBody LicenseCheck check){License l=licenses.findByKeyHash(digest(check.key().trim())).orElse(null);boolean valid=l!=null&&l.product.equalsIgnoreCase(check.product())&&"ACTIVE".equals(l.status)&&(l.expiresAt==null||l.expiresAt.isAfter(Instant.now()));if(valid){String serverHash=digest(check.serverId().trim());synchronized(l){if(!l.activatedServers.contains(serverHash)&&l.activatedServers.size()>=l.maxActivations)valid=false;else if(l.activatedServers.add(serverHash)){l.activations=l.activatedServers.size();licenses.save(l);}}}return ResponseEntity.ok(Map.of("valid",valid,"product",valid?l.product:"","status",valid?"ACTIVE":"INVALID"));}
    @GetMapping("/admin") String admin(Authentication auth,Model m){m.addAttribute("active","admin");m.addAttribute("users",users.count());m.addAttribute("accounts",users.findAllByOrderByCreatedAtAsc());m.addAttribute("ownerId",settings.findById("global").orElseThrow().ownerId);m.addAttribute("canManageAdmins",isOwner(auth));m.addAttribute("accountForm",new NewAccount("","","USER"));m.addAttribute("licenseCount",licenses.count());m.addAttribute("releases",releases.findAllByOrderByCreatedAtDesc());m.addAttribute("licenseForm",new NewLicense("","",1,0));m.addAttribute("releaseForm",new NewRelease("","","","stable"));m.addAttribute("licenseItems",licenses.findAllByOrderByCreatedAtDesc());return isAdmin(auth)?"admin":"redirect:/app";}
    @PostMapping("/admin/users") String createAccount(@Valid @ModelAttribute("accountForm") NewAccount form,BindingResult errors,Authentication auth,RedirectAttributes flash){if(errors.hasErrors()||(!"USER".equals(form.role())&&!isOwner(auth))){flash.addFlashAttribute("error","Check the account details and your permissions.");return "redirect:/admin";}String email=form.email().trim().toLowerCase(Locale.ROOT);if(users.findByEmailIgnoreCase(email).isPresent()){flash.addFlashAttribute("error","An account already uses that email.");return "redirect:/admin";}users.save(new Account(email,encoder.encode(form.password()),form.role()));flash.addFlashAttribute("notice","Account created.");return "redirect:/admin";}
    @PostMapping("/admin/licenses") String createLicense(@Valid @ModelAttribute NewLicense form,BindingResult errors,RedirectAttributes flash){if(errors.hasErrors()){flash.addFlashAttribute("error","Check the product, owner email and activation limits.");return "redirect:/admin";}String raw="NORD-"+randomToken();Instant expiry=form.days()>0?Instant.now().plus(Duration.ofDays(form.days())):null;licenses.save(new License(form.product().trim(),form.ownerEmail().trim().toLowerCase(Locale.ROOT),digest(raw),raw.substring(0,9),form.activations(),expiry));flash.addFlashAttribute("issuedKey",raw);return "redirect:/admin";}
    @PostMapping("/admin/releases") String createRelease(@Valid @ModelAttribute NewRelease form,BindingResult errors,RedirectAttributes flash){if(errors.hasErrors()){flash.addFlashAttribute("error","Use a semantic version (for example 1.2.0), title, and stable or beta channel.");return "redirect:/admin";}Release r=new Release(form.version(),form.title(),form.notes(),form.channel());releases.save(r);flash.addFlashAttribute("notice","Update draft created.");return "redirect:/admin";}
    @PostMapping("/admin/releases/{id}/publish") String publish(@PathVariable Long id,RedirectAttributes flash){Release r=releases.findById(id).orElseThrow();r.status="PUBLISHED";releases.save(r);flash.addFlashAttribute("notice","Release "+r.version+" published to the "+r.channel+" update channel.");return "redirect:/admin";}
    @PostMapping("/admin/licenses/{id}/revoke") String revoke(@PathVariable Long id,RedirectAttributes flash){License l=licenses.findById(id).orElseThrow();l.status="REVOKED";licenses.save(l);flash.addFlashAttribute("notice","License for "+l.product+" revoked.");return "redirect:/admin";}
    @GetMapping("/api/updates/{channel}") @ResponseBody ResponseEntity<Map<String,Object>> updateFeed(@PathVariable String channel){return releases.findFirstByChannelAndStatusOrderByCreatedAtDesc(channel,"PUBLISHED").map(r->ResponseEntity.ok(Map.<String,Object>of("version",r.version,"title",r.title,"notes",Objects.toString(r.notes,""),"channel",r.channel,"publishedAt",r.createdAt.toString()))).orElseGet(()->ResponseEntity.notFound().build());}
    @GetMapping("/docs") String docs(Model m){m.addAttribute("active","docs");return "docs";}
    @GetMapping("/updates") String updates(Model m){m.addAttribute("active","updates");m.addAttribute("releases",releases.findAllByOrderByCreatedAtDesc().stream().filter(r->"PUBLISHED".equals(r.status)).toList());return "updates";}
    private boolean isOwner(Authentication a){return a!=null&&users.findByEmailIgnoreCase(a.getName()).map(u->"OWNER".equals(u.role)).orElse(false);}
    private boolean isAdmin(Authentication a){return a!=null&&a.getAuthorities().stream().anyMatch(g->g.getAuthority().equals("ROLE_ADMIN")||g.getAuthority().equals("ROLE_OWNER"));}
    private static String digest(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static String randomToken(){byte[] b=new byte[24];new SecureRandom().nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b).toUpperCase(Locale.ROOT);}
    private static String safe(String s){return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]","-").replaceAll("-+","-");}
}
