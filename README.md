# Nord Dev

Java 21 developer platform with global Nord ID sign-in, a first-run setup wizard, service templates, license management, and admin-managed releases.

## One-command server install

On a fresh Debian/Ubuntu server with internet access and SSH access:

```bash
curl -fsSL https://raw.githubusercontent.com/riguita-nord/nord_dev/main/nord-dev-installer.run -o nord-dev-installer.run && chmod +x nord-dev-installer.run && sudo ./nord-dev-installer.run install
```

Then open `http://<server-ip>:8080/setup`. The first account created in the wizard becomes the protected **Platform Owner**. Later accounts are created inside Admin; only the Owner can create another administrator. The wizard saves the platform name, public URL, and timezone.

The installer installs Java 21 and Maven, builds and verifies the app, creates the `norddev` system account and systemd service, and configures a persistent H2 database. It supports `install`, `update`, `status`, `logs`, `restart`, and `stop`. Updates preserve `/etc/nord-dev/nord-dev.env` and `/var/lib/nord-dev/data`, and save a pre-update archive under `/var/backups/nord-dev/`. Configure HTTPS through a reverse proxy before exposing the service publicly; setting the public URL in setup does not itself provision TLS.

For a manual local run, use Java 21 and Maven 3.9+:

```bash
mvn spring-boot:run
```

Open `http://localhost:8080/setup` and finish the wizard. The default H2 database lives in `./data`; database settings can be overridden with `NORD_DB_URL`, `NORD_DB_USER`, and `NORD_DB_PASSWORD`.

## Platform areas

- **Global Nord ID:** BCrypt password storage, first-user Platform Owner, and admin-managed accounts with role restrictions.
- **Service studio:** Java CLI, HTTP service, background worker, Discord integration, FiveM resource, and NUI starter models. Each project generates an owner-scoped ZIP scaffold.
- **Licensing:** admin-generated high-entropy keys; only SHA-256 digests are stored. `/api/licenses/verify` checks product, expiry, and activation allowance and binds a key to a stable installation ID.
- **Updates:** admin release drafts for `stable` and `beta`, publish action, release history, and public JSON metadata feeds at `/api/updates/{channel}`.
- **Docs:** in-app developer setup and integration notes.

## Deployment notes

Before public production use, configure HTTPS, PostgreSQL or another managed database, automated off-server backups, email verification and recovery, rate limiting, audit logs, external secret storage, and a security review. License activation persistence should use atomic transactional storage with a unique activation constraint before horizontal scaling. Update feeds publish metadata; signed binary artifacts, staged rollouts, and client updater execution are not implemented. Never put private license keys in FiveM client code or NUI assets; verify them from a trusted server-side resource.
