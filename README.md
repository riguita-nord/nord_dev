# Nord Forge V2

Nord Forge V2 is the Java rewrite of the Nord development platform. It replaces the previous nord_dev prototype and ports the Forge product model into a service-oriented Java 21 / Quarkus runtime inspired by Nord SaaS Lite.

## Product surfaces

Forge V2 uses three hard product boundaries. **Client Area** is for customers, **Developer Studio** starts at a dedicated Workspace Hub, and **Administration** is a separate service launched only by the Platform Owner.


- **Client Area** — account, Forge Key, purchased products, licenses, marketplace, downloads and support.
- **Developer Studio** — isolated workspace surface for products, releases, licensing, docs, public pages, team, purchases, infrastructure, integrations and audit.
- **Administration** — independent Java service. Platform Owner launches it from Forge through a signed short-lived SSO handoff.
- **Runtime licensing API** — Forge Key + product validation for FiveM/server integrations.
- **Installer/update system** — commit-pinned GitHub source, build-before-stop update transaction, backup, health checks and rollback.

The Client Area and Developer Studio intentionally do not share a sidebar. A global app rail changes surface; developer workspaces only exist inside Developer Studio.

## Runtime

- Java 21
- Quarkus
- H2 file database
- REST/Jackson
- dedicated Administration service
- systemd installation
- no hard-coded production credentials

## Install

```bash
chmod +x nord-forge-installer.run
sudo ./nord-forge-installer.run install
```

Update:

```bash
sudo ./nord-forge-installer.run update
```

Status:

```bash
nord-forge status
```

The first account registered becomes the protected Platform Owner.

## Ports

- Core/Web: `8088`
- Administration: `8089` (should normally be exposed only through the intended reverse proxy / firewall policy)

## Data

Default persistent root: `/var/lib/nord-forge`.

Release artifacts are stored under `/var/lib/nord-forge/storage/releases`. Database state is stored under `/var/lib/nord-forge/db`.

## V1 capability map

V2's domain model includes: accounts, Forge Keys, workspaces, members/RBAC, products, releases, entitlements, licenses and activations, one-use downloads, marketplace/store data, manual purchase conversations, support, documentation/pages, generic integrations (Tebex/Discord), infrastructure nodes, API keys, workspace audit and isolated platform administration.

Source-protection is deliberately separated from authorization. V2 never pretends that UI hiding protects code: runtime licensing and protected-build work are independent concerns and can evolve without weakening download entitlement checks.
