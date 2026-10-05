package dev.nordlab.norddev;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity @Table(name="nd_users") class Account {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(nullable=false,unique=true) String email;
    @Column(nullable=false) String passwordHash;
    @Column(nullable=false) String role="USER";
    @Column(nullable=false) Instant createdAt=Instant.now();
    protected Account(){} Account(String email,String hash,String role){this.email=email;this.passwordHash=hash;this.role=role;}
}
@Entity @Table(name="nd_platform_settings") class PlatformSettings {
    @Id String id="global";
    @Column(nullable=false) String name="Nord Dev";
    @Column(nullable=false) String publicUrl="";
    @Column(nullable=false) String timezone="Europe/Lisbon";
    @Column(nullable=false) boolean setupComplete=false;
    Long ownerId;
    Instant configuredAt;
    protected PlatformSettings(){}
}
@Entity @Table(name="nd_licenses") class License {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(nullable=false) String product;
    @Column(nullable=false) String ownerEmail;
    @Column(nullable=false,unique=true) String keyHash;
    @Column(nullable=false) String keyPrefix;
    @Column(nullable=false) String status="ACTIVE";
    @Column(nullable=false) int activations=0;
    @Column(nullable=false) int maxActivations=1;
    Instant expiresAt;
    @Column(nullable=false) Instant createdAt=Instant.now();
    @ElementCollection(fetch=FetchType.EAGER) @CollectionTable(name="nd_license_servers",joinColumns=@JoinColumn(name="license_id"),uniqueConstraints=@UniqueConstraint(columnNames={"license_id","server_hash"})) @Column(name="server_hash",nullable=false) Set<String> activatedServers=new HashSet<>();
    protected License(){}
    License(String product,String owner,String hash,String prefix,int max,Instant expires){this.product=product;this.ownerEmail=owner;keyHash=hash;keyPrefix=prefix;maxActivations=max;expiresAt=expires;}
}
@Entity @Table(name="nd_services") class ServiceProject {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(nullable=false) String name;
    @Column(nullable=false) String template;
    @Column(nullable=false) String ownerEmail;
    @Column(nullable=false) String status="READY";
    @Column(nullable=false) Instant createdAt=Instant.now();
    protected ServiceProject(){} ServiceProject(String name,String template,String owner){this.name=name;this.template=template;ownerEmail=owner;}
}
@Entity @Table(name="nd_releases") class Release {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(nullable=false) String version;
    @Column(nullable=false) String title;
    @Column(length=5000) String notes;
    @Column(nullable=false) String channel="stable";
    @Column(nullable=false) String status="DRAFT";
    @Column(nullable=false) Instant createdAt=Instant.now();
    protected Release(){} Release(String version,String title,String notes,String channel){this.version=version;this.title=title;this.notes=notes;this.channel=channel;}
}
@Entity @Table(name="nd_workspaces") class Workspace {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(nullable=false) String name;
    @Column(nullable=false) String area="DEVELOPER";
    @Column(length=500) String description="";
    @Column(nullable=false) String ownerEmail;
    @Column(nullable=false) Instant createdAt=Instant.now();
    protected Workspace(){}
    Workspace(String name,String area,String description,String ownerEmail){this.name=name;this.area=area;this.description=description;this.ownerEmail=ownerEmail;}
}
@Entity @Table(name="nd_workspace_messages") class WorkspaceMessage {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(nullable=false) Long workspaceId;
    @Column(nullable=false) String channel;
    @Column(nullable=false) String authorEmail;
    @Column(nullable=false,length=2000) String content;
    @Column(nullable=false) Instant createdAt=Instant.now();
    protected WorkspaceMessage(){}
    WorkspaceMessage(Long workspaceId,String channel,String authorEmail,String content){this.workspaceId=workspaceId;this.channel=channel;this.authorEmail=authorEmail;this.content=content;}
}
