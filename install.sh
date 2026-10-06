#!/usr/bin/env bash
set -Eeuo pipefail
umask 027

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

ACTION="${1:-install}"
ROOT="/opt/nord-forge"
ETC="/etc/nord-forge"
DATA="/var/lib/nord-forge"
BACKUPS="$DATA/backups"
LEGACY_BACKUPS="/var/backups/nord-forge"
ENV_FILE="$ETC/nord.env"
VERSION="$(tr -d '[:space:]' < "$SCRIPT_DIR/VERSION")"
BACKUP_ARCHIVE=""
DB_RESET_MARKER="$DATA/control/forge-v2-clean-db"
DB_RESET_PERFORMED=0

log(){ printf '[Nord Forge] %s\n' "$*"; }
warn(){ printf '[Nord Forge] WARNING: %s\n' "$*" >&2; }
die(){ printf '[Nord Forge] ERROR: %s\n' "$*" >&2; exit 1; }

[[ ${EUID:-$(id -u)} -eq 0 ]] || die "Run with sudo."

install_deps(){
  if ! command -v java >/dev/null 2>&1 || ! java -version 2>&1 | grep -q '"21\.'; then
    apt-get update -y
    DEBIAN_FRONTEND=noninteractive apt-get install -y openjdk-21-jdk
  fi
  command -v mvn >/dev/null 2>&1 || {
    apt-get update -y
    DEBIAN_FRONTEND=noninteractive apt-get install -y maven
  }
  command -v curl >/dev/null 2>&1 || {
    apt-get update -y
    DEBIAN_FRONTEND=noninteractive apt-get install -y curl
  }
}

ensure_user(){
  id nordforge >/dev/null 2>&1 || useradd --system --home "$DATA" --shell /usr/sbin/nologin nordforge

  install -d -o root -g nordforge -m 0750 "$ROOT"
  install -d -o root -g nordforge -m 0755 "$ROOT/bin"
  install -d -o root -g nordforge -m 0750 "$ETC"

  install -d -o nordforge -g nordforge -m 0750 "$DATA"
  install -d -o nordforge -g nordforge -m 0750 "$DATA/db"
  install -d -o nordforge -g nordforge -m 0750 "$DATA/storage"
  install -d -o nordforge -g nordforge -m 0750 "$DATA/storage/releases"
  install -d -o nordforge -g nordforge -m 0750 "$DATA/storage/protection-modules"
  install -d -o nordforge -g nordforge -m 0750 "$DATA/control"
  install -d -o nordforge -g nordforge -m 0750 "$BACKUPS"

  install -d -o root -g root -m 0750 "$LEGACY_BACKUPS"
}

secret(){
  od -An -N"${1:-48}" -tx1 /dev/urandom | tr -d ' \n'
}

env_has(){
  grep -q "^${1}=" "$ENV_FILE" 2>/dev/null
}

append_env(){
  local key="$1" value="$2"
  env_has "$key" || printf '%s=%s\n' "$key" "$value" >> "$ENV_FILE"
}

ensure_env(){
  if [[ ! -f "$ENV_FILE" ]]; then
    cat > "$ENV_FILE" <<EOF
PORT=8088
NORD_ADMIN_PORT=8089
NORD_FORGE_DATA_DIR=$DATA
NORD_PUBLIC_URL=http://127.0.0.1:8088
NORD_ADMIN_URL=/administration
NORD_COOKIE_SECURE=false
NORD_SESSION_DAYS=14
NORD_ADMIN_SESSION_MINUTES=60
NORD_ADMIN_SSO_SECRET=$(secret 48)
NORD_ADMIN_SERVICE_SECRET=$(secret 48)
NORD_PROTECTION_SECRET=$(secret 48)
EOF
  else
    if grep -qx 'NORD_ADMIN_URL=http://127\.0\.0\.1:8089' "$ENV_FILE"; then
      sed -i 's#^NORD_ADMIN_URL=.*#NORD_ADMIN_URL=/administration#' "$ENV_FILE"
    fi
    append_env NORD_ADMIN_URL "/administration"
    append_env NORD_ADMIN_SSO_SECRET "$(secret 48)"
    append_env NORD_ADMIN_SERVICE_SECRET "$(secret 48)"
    append_env NORD_PROTECTION_SECRET "$(secret 48)"
  fi

  chown root:nordforge "$ENV_FILE"
  chmod 0640 "$ENV_FILE"
}

build(){
  log "Building Nord Forge V$VERSION before touching the running release..."
  mvn -B -DskipTests package
  [[ -f "core/target/nord-forge-core-$VERSION-runner.jar" ]] || die "Core runner missing."
  [[ -f "admin-service/target/nord-forge-admin-$VERSION-runner.jar" ]] || die "Admin runner missing."
}

stop_services(){
  systemctl stop nord-forge-admin.service nord-forge.service 2>/dev/null || true
}

backup(){
  local stamp
  local entries=()

  [[ -d "$ROOT" ]] && entries+=("opt/nord-forge")
  [[ -d "$ETC" ]] && entries+=("etc/nord-forge")
  [[ -d "$DATA/db" ]] && entries+=("var/lib/nord-forge/db")

  if [[ ${#entries[@]} -eq 0 ]]; then
    log "No previous installation to back up."
    return 0
  fi

  stamp="$(date +%Y%m%d-%H%M%S)"
  BACKUP_ARCHIVE="$BACKUPS/pre-update-to-v${VERSION}-${stamp}.tar.gz"
  tar -C / -czf "$BACKUP_ARCHIVE" "${entries[@]}"
  chmod 0640 "$BACKUP_ARCHIVE"
  chown root:nordforge "$BACKUP_ARCHIVE"
  log "Pre-update snapshot: $BACKUP_ARCHIVE"
}

reset_legacy_database_once(){
  if [[ -f "$DB_RESET_MARKER" ]]; then
    return 0
  fi

  if [[ -d "$DATA/db" ]] && find "$DATA/db" -mindepth 1 -print -quit 2>/dev/null | grep -q .; then
    warn "Legacy/pre-V2 database detected. A backup was created and the database will be replaced with a clean Forge V2 schema."
    rm -rf "$DATA/db"
    install -d -o nordforge -g nordforge -m 0750 "$DATA/db"
    DB_RESET_PERFORMED=1
  else
    DB_RESET_PERFORMED=1
  fi
}

commit_database_generation(){
  if [[ "$DB_RESET_PERFORMED" -eq 1 ]]; then
    install -d -o nordforge -g nordforge -m 0750 "$DATA/control"
    printf '%s\n' "Forge V2 clean database initialized at $(date -u +%FT%TZ)" > "$DB_RESET_MARKER"
    chown root:nordforge "$DB_RESET_MARKER"
    chmod 0640 "$DB_RESET_MARKER"
  fi
}

legacy_full_backup(){
  local stamp archive entries=()
  mkdir -p "$LEGACY_BACKUPS"
  chmod 0750 "$LEGACY_BACKUPS"
  stamp="$(date +%Y%m%d-%H%M%S)"
  archive="$LEGACY_BACKUPS/legacy-nord-dev-before-forge-v2-${stamp}.tar.gz"

  for p in \
    opt/nord-dev opt/nord_dev opt/nord-forge \
    etc/nord-dev etc/nord_dev etc/nord-forge \
    var/lib/nord-dev var/lib/nord_dev var/lib/nord-forge; do
    [[ -e "/$p" ]] && entries+=("$p")
  done

  if [[ ${#entries[@]} -gt 0 ]]; then
    tar -C / -czf "$archive" "${entries[@]}"
    chmod 0600 "$archive"
    log "Full legacy snapshot: $archive"
  else
    log "No legacy application directories were found to back up."
  fi
}

remove_legacy_installation(){
  warn "Removing the complete legacy Nord Dev/Forge installation and all legacy application data."

  systemctl stop nord-dev.service nord_dev.service nord-forge.service nord-forge-admin.service 2>/dev/null || true
  systemctl disable nord-dev.service nord_dev.service 2>/dev/null || true

  rm -rf \
    /opt/nord-dev /opt/nord_dev /opt/nord-forge \
    /etc/nord-dev /etc/nord_dev /etc/nord-forge \
    /var/lib/nord-dev /var/lib/nord_dev /var/lib/nord-forge

  rm -f \
    /etc/systemd/system/nord-dev.service \
    /etc/systemd/system/nord_dev.service \
    /etc/systemd/system/nord-forge.service \
    /etc/systemd/system/nord-forge-admin.service \
    /etc/systemd/system/nord-forge-update.service \
    /etc/systemd/system/nord-forge-update.path \
    /usr/local/bin/nord-dev \
    /usr/local/bin/nord_dev \
    /usr/local/bin/nord-forge \
    /usr/local/libexec/nord-forge-update-worker

  systemctl daemon-reload
  ensure_user
  ensure_env
}

install_files(){
  ensure_user

  install -o root -g nordforge -m 0755 "core/target/nord-forge-core-$VERSION-runner.jar" "$ROOT/bin/nord-forge-core.jar"
  install -o root -g nordforge -m 0755 "admin-service/target/nord-forge-admin-$VERSION-runner.jar" "$ROOT/bin/nord-forge-admin.jar"
  printf '%s\n' "$VERSION" > "$ROOT/VERSION"
  chown root:nordforge "$ROOT/VERSION"
  chmod 0644 "$ROOT/VERSION"

  [[ -r "$ROOT/bin/nord-forge-core.jar" ]] || die "Installed core JAR is missing or unreadable."
  [[ -r "$ROOT/bin/nord-forge-admin.jar" ]] || die "Installed admin JAR is missing or unreadable."
  runuser -u nordforge -- test -r "$ROOT/bin/nord-forge-core.jar" || die "nordforge cannot read the core JAR."
  runuser -u nordforge -- test -r "$ROOT/bin/nord-forge-admin.jar" || die "nordforge cannot read the admin JAR."

  install -m 0755 scripts/nord-forge /usr/local/bin/nord-forge
  install -m 0755 -d /usr/local/libexec
  install -m 0755 scripts/nord-forge-update-worker /usr/local/libexec/nord-forge-update-worker

  install -m 0755 nord-forge-installer.run "$ROOT/nord-forge-installer.run"
  install -m 0755 install.sh "$ROOT/install.sh"

  install -m 0644 deploy/nord-forge.service /etc/systemd/system/nord-forge.service
  install -m 0644 deploy/nord-forge-admin.service /etc/systemd/system/nord-forge-admin.service
  install -m 0644 deploy/nord-forge-update.service /etc/systemd/system/nord-forge-update.service
  install -m 0644 deploy/nord-forge-update.path /etc/systemd/system/nord-forge-update.path

  systemctl daemon-reload
  systemctl enable --now nord-forge-update.path >/dev/null 2>&1 || true
}

health_check(){
  local ok=0 core_url admin_url
  core_url="http://127.0.0.1:8088/api/v2/setup/status"
  admin_url="http://127.0.0.1:8089/administration/api/health"

  for _ in $(seq 1 45); do
    if systemctl is-active --quiet nord-forge.service &&
       systemctl is-active --quiet nord-forge-admin.service &&
       curl -fsS --max-time 3 "$core_url" >/dev/null 2>&1 &&
       curl -fsS --max-time 3 "$admin_url" >/dev/null 2>&1; then
      ok=1
      break
    fi
    sleep 1
  done

  if [[ "$ok" -ne 1 ]]; then
    warn "Application health verification failed."
    warn "Core probe: $core_url"
    curl -sS -i --max-time 3 "$core_url" 2>&1 | head -n 20 || true
    warn "Administration probe: $admin_url"
    curl -sS -i --max-time 3 "$admin_url" 2>&1 | head -n 20 || true
    systemctl --no-pager --full status nord-forge.service nord-forge-admin.service || true
  fi

  [[ "$ok" -eq 1 ]]
}

start_services(){
  systemctl enable nord-forge.service nord-forge-admin.service >/dev/null 2>&1 || true
  systemctl restart nord-forge.service nord-forge-admin.service
}

show_failure_logs(){
  journalctl -u nord-forge -n 100 --no-pager || true
  journalctl -u nord-forge-admin -n 100 --no-pager || true
}

rollback(){
  [[ -n "$BACKUP_ARCHIVE" && -f "$BACKUP_ARCHIVE" ]] || return 1

  warn "New release failed health verification; restoring the previous release automatically."
  stop_services
  rm -rf "$ROOT" "$DATA/db"
  tar -xzf "$BACKUP_ARCHIVE" -C /
  chown -R nordforge:nordforge "$DATA"
  systemctl daemon-reload
  systemctl enable nord-forge.service nord-forge-admin.service >/dev/null 2>&1 || true
  systemctl restart nord-forge.service nord-forge-admin.service || true

  if health_check; then
    log "Rollback completed successfully."
    return 0
  fi

  show_failure_logs
  return 1
}

case "$ACTION" in
  install)
    install_deps
    ensure_user
    build
    stop_services
    backup
    reset_legacy_database_once
    ensure_env
    install_files
    start_services
    if ! health_check; then
      show_failure_logs
      if rollback; then
        die "Installation failed; the previous release/database was restored."
      fi
      die "Initial installation failed health verification."
    fi
    commit_database_generation
    log "Nord Forge V$VERSION installed and healthy. Open the web UI to create the first Platform Owner."
    ;;

  update)
    install_deps
    ensure_user
    build
    stop_services
    backup
    reset_legacy_database_once
    ensure_env

    if install_files && start_services && health_check; then
      commit_database_generation
      log "Nord Forge V$VERSION update committed successfully. If this was the legacy migration, open the web UI to create the first Platform Owner."
    else
      show_failure_logs
      if rollback; then
        die "Update failed; the previous release was restored."
      fi
      die "Update failed and automatic rollback also failed. Inspect systemd logs and $BACKUP_ARCHIVE."
    fi
    ;;

  replace)
    install_deps
    build
    legacy_full_backup
    remove_legacy_installation
    install_files
    start_services
    if ! health_check; then
      show_failure_logs
      die "Clean Forge V2 replacement failed health verification. Legacy backup remains under $LEGACY_BACKUPS."
    fi
    DB_RESET_PERFORMED=1
    commit_database_generation
    log "Legacy Nord Dev installation was fully removed and Nord Forge V$VERSION was installed from scratch."
    log "Open the web UI and complete Initial Setup to create the Platform Owner."
    ;;

  uninstall)
    stop_services
    systemctl disable nord-forge.service nord-forge-admin.service >/dev/null 2>&1 || true
    systemctl disable --now nord-forge-update.path 2>/dev/null || true
    rm -f \
      /etc/systemd/system/nord-forge.service \
      /etc/systemd/system/nord-forge-admin.service \
      /etc/systemd/system/nord-forge-update.service \
      /etc/systemd/system/nord-forge-update.path \
      /usr/local/bin/nord-forge \
      /usr/local/libexec/nord-forge-update-worker
    systemctl daemon-reload
    log "Services removed. Persistent data remains at $DATA."
    ;;

  *)
    die "Usage: install.sh [install|update|replace|uninstall]"
    ;;
esac
