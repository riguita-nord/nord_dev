# Nord Dev

Java 21 developer platform for the Nord ecosystem. This first application release includes global Nord ID sign-in, account registration, private service workspaces, service template ZIP exports, Nord license management, an activation verification API, admin release controls, channel feeds, and in-app release history.

## Run locally

Requirements: Java 21 and Maven 3.9+.

```bash
export NORD_ADMIN_EMAIL="admin@example.com"
export NORD_ADMIN_PASSWORD="choose-a-unique-password-at-least-14-chars"
mvn spring-boot:run
```

Open `http://localhost:8080`. Create user accounts through **Create an account**. The configured bootstrap email is given the `ADMIN` role. The default H2 database is stored under `./data`; configure `NORD_DB_URL`, `NORD_DB_USER`, and `NORD_DB_PASSWORD` to use another database. Do not commit credentials.

## Platform areas

- **Global Nord ID:** one account and secure session for the application, with BCrypt password storage and role-gated administration.
- **Service studio:** Java CLI, HTTP service, background worker, Discord integration, FiveM resource, and NUI starter models. Each project generates an owner-scoped ZIP scaffold.
- **Licensing:** admin-generated, high-entropy keys; only SHA-256 digests are stored. `/api/licenses/verify` checks product, expiry, and activation allowance and binds a key to a stable installation ID.
- **Updates:** admin release drafts for `stable` and `beta`, publish action, release history, and public JSON metadata feeds at `/api/updates/{channel}`.
- **Docs:** in-app developer setup and integration notes.

## Deployment notes

This is an initial production-oriented application baseline, not a fully hardened public SaaS deployment. Before exposing it publicly, configure HTTPS, PostgreSQL or another managed database, automated backups, email verification and recovery, rate limiting, audit logs, external secret storage, and a security review. License activation is persisted but should be moved to atomic transactional storage with a unique activation constraint before horizontally scaling multiple app nodes. The update feeds publish metadata; binary artifact hosting, signatures, staged rollouts, and client updater execution are not implemented yet. Do not put private license keys in FiveM client code or NUI assets; perform checks from a trusted server-side resource.
