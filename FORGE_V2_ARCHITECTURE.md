# Nord Forge V2 Architecture

Nord Forge V2 is a Java 21 platform built around three deliberately separated product surfaces.

## 1. Client Area

Customer-facing surface only. It owns account/profile state, Forge Key, Keymaster bindings, purchased/granted products, downloads, runtime licenses, marketplace, purchase requests and customer support.

Client Area never exposes developer workspace navigation or workspace administration.

## 2. Developer Studio

Developer-only surface. Entering Developer Studio first opens the Workspace Hub. A workspace must be selected before workspace-scoped tools are shown.

Each workspace owns:
- products and draft/published state;
- releases, artifacts and changelogs;
- licensing and runtime protection;
- purchase conversations;
- support inbox;
- storefront configuration;
- documentation and website pages;
- Tebex and Discord integrations;
- team/RBAC;
- infrastructure nodes and API keys;
- audit trail.

Workspace data is isolated by workspace_id and every server-side mutation validates membership/role.

## 3. Administration

Administration is a separate Java service and is not another Developer Studio workspace. Only the Platform Owner sees the Administration launcher. Forge Core issues a signed, short-lived SSO handoff to the admin service.

Administration owns global platform state such as users, global settings, operational health and platform-wide controls.

## Runtime layout

- core/ — Quarkus Core/API/Web runtime on port 8088.
- admin-service/ — isolated Quarkus Administration runtime on port 8089.
- fivem-resource/ — runtime licensing integration for FiveM.
- scripts/ — CLI and update worker.
- deploy/ — systemd service definitions.
- install.sh — transactional installer/update implementation.
- nord-forge-installer.run — permanent trusted GitHub launcher.

## Update model

The updater follows the same operational principles as Nord SaaS Lite:

1. resolve and pin an exact trusted GitHub commit;
2. download that immutable source;
3. verify the Forge V2 source layout;
4. build before stopping the current production services;
5. snapshot the previous installation and persistent database state;
6. install Core and Administration;
7. restart services and perform readiness checks;
8. automatically restore the previous release if the new release fails health verification.

Persistent customer data lives outside the application directory under /var/lib/nord-forge.

## Forge V1 capability migration

The Java V2 domain currently covers the core Forge V1 capability set: accounts, Forge Keys, Keymaster identity binding, workspaces, team/RBAC, products, releases, marketplace, entitlements, licenses, activations, one-use downloads, manual purchases, support, documentation, public pages/storefronts, Tebex, Discord, infrastructure, API keys, auditing and protected runtime/build identities.

The V2 rule is that feature visibility in the browser is never treated as authorization. Workspace boundaries, download entitlements, licensing and platform administration are enforced by the Java backend.
