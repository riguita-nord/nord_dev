#!/usr/bin/env bash
set -Eeuo pipefail
umask 027

ACTION="${1:-install}"
ROOT="/opt/nord-forge"
ETC="/etc/nord-forge"
DATA="/var/lib/nord-forge"
BACKUPS="$DATA/backups"
ENV_FILE="$ETC/nord.env"
VERSION="$(tr -d '[:space:]' < VERSION)"
BACKUP_ARCHIVE=""

log(){ printf '[Nord Forge] %s\n' "$*"; }
warn(){ printf '[Nord Forge] WARNING: %s\n' "$*" >&2; }
die(){ printf '[Nord Forge] ERROR: %s\n' "$*" >&2; exit 1; }
[[ ${EUID:-$(id -u)} -eq 0 ]] || die "Run with sudo."

install_deps(){
  command -v java >/dev/null 2>&1 && java -version 2>&1 | grep -q '"21\.' || {
    apt-get update -y
    DEBIAN_FRONTEND=noninteractive apt-get install -y openjdk-21-jdk
  }
  command -v mvn >/dev/null 2>&1 || { apt-get update -y; DEBIAN_FRONTEND=noninteractive apt-get install -y maven; }
  command -v curl >/dev/null 2>&1 || { apt-get update -y; DEBIAN_FRONTEND=noninteractive apt-get install -y curl; }
}

ensure_user(){
  id nordforge >/dev/null 2>&1 || useradd --system --home "$DATA" --shell /usr/sbin/nologin nordforge
  mkdir -p "$ROOT" "$ETC" "$DATA/db" "$DATA/storage/releases" "$DATA/storage/protection-modules" "$BACKUPS"
  chown -R nordforge:nordforge "$DATA"
}

secret(){ od -An -N"${1:-48}" -tx1 /dev/urandom | tr -d ' \n'; }

env_has(){ grep -q "^${1}=" "$ENV_FILE" 2>/dev/null; }
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
NORD_ADMIN_URL=http://127.0.0.1:8089
NORD_COOKIE_SECURE=false
NORD_SESSION_DAYS=14
NORD_ADMIN_SESSION_MINUTES=60
NORD_ADMIN_SSO_SECRET=$(secret 48)
NORD_ADMIN_SERVICE_SECRET=$(secret 48)
NORD_PROTECTION_SECRET=$(secret 48)
EOF
  else
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
  local stamp entries=()
  stamp="$(date +%Y%m%d-%H%M%S)"
  [[ -d "$ROOT" ]] && entries+=("$ROOT")
  [[ -d "$ETC" ]] && entries+=("$ETC")
  [[ -d "$DATA/db" ]] && entries+=("$DATA/db")
  if [[ ${#entries[@]} -eq 0 ]]; then
    log "No previous installation to back up."
    return 0
  fi
  BACKUP_ARCHIVE="$BACKUPS/pre-${ACTION}-v${VERSION}-${stamp}.tar.gz"
  tar -czf "$BACKUP_ARCHIVE" --absolute-names "${entries[@]}"
  chmod 0600 "$BACKUP_ARCHIVE"
  log "Pre-update snapshot: $BACKUP_ARCHIVE"
}

install_files(){
  install -m 0755 -d "$ROOT/bin"
  install -m 0755 "core/target/nord-forge-core-$VERSION-runner.jar" "$ROOT/bin/nord-forge-core.jar"
  install -m 0755 "admin-service/target/nord-forge-admin-$VERSION-runner.jar" "$ROOT/bin/nord-forge-admin.jar"
  printf '%s\n' "$VERSION" > "$ROOT/VERSION"
  install -m 0755 scripts/nord-forge /usr/local/bin/nord-forge
  install -m 0755 nord-forge-installer.run "$ROOT/nord-forge-installer.run"
  install -m 0755 install.sh "$ROOT/install.sh"
  install -m 0644 deploy/nord-forge.service /etc/systemd/system/nord-forge.service
  install -m 0644 deploy/nord-forge-admin.service /etc/systemd/system/nord-forge-admin.service
  systemctl daemon-reload
}

health_check(){
  local ok=0
  for _ in $(seq 1 35); do
    if curl -fsS http://127.0.0.1:8088/q/health/ready >/dev/null 2>&1 &&
       curl -fsS http://127.0.0.1:8089/q/health/ready >/dev/null 2>&1; then
      ok=1
      break
    fi
    sleep 1
  done
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
  rm -rf "$ROOT"
  tar -xzf "$BACKUP_ARCHIVE" -C /
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
    ensure_env
    build
    install_files
    start_services
    if ! health_check; then
      show_failure_logs
      die "Initial installation failed health verification."
    fi
    log "Nord Forge V$VERSION installed and healthy."
    ;;
  update)
    install_deps
    ensure_user
    ensure_env
    build
    stop_services
    backup
    if install_files && start_services && health_check; then
      log "Nord Forge V$VERSION update committed successfully."
    else
      show_failure_logs
      if rollback; then
        die "Update failed; the previous release was restored."
      fi
      die "Update failed and automatic rollback also failed. Inspect systemd logs and $BACKUP_ARCHIVE."
    fi
    ;;
  uninstall)
    stop_services
    systemctl disable nord-forge.service nord-forge-admin.service >/dev/null 2>&1 || true
    rm -f /etc/systemd/system/nord-forge.service /etc/systemd/system/nord-forge-admin.service /usr/local/bin/nord-forge
    systemctl daemon-reload
    log "Services removed. Persistent data remains at $DATA."
    ;;
  *)
    die "Usage: install.sh [install|update|uninstall]"
    ;;
esac
