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

log(){ printf '[Nord Forge] %s\n' "$*"; }
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
  mkdir -p "$ROOT" "$ETC" "$DATA/db" "$DATA/storage/releases" "$BACKUPS"
  chown -R nordforge:nordforge "$DATA"
}
secret(){ od -An -N"${1:-48}" -tx1 /dev/urandom | tr -d ' \n'; }
ensure_env(){
  [[ -f "$ENV_FILE" ]] || cat > "$ENV_FILE" <<EOF
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
EOF
  chown root:nordforge "$ENV_FILE"; chmod 0640 "$ENV_FILE"
}
build(){
  log "Building Nord Forge V$VERSION..."
  mvn -B -DskipTests package
  [[ -f "core/target/nord-forge-core-$VERSION-runner.jar" ]] || die "Core runner missing."
  [[ -f "admin-service/target/nord-forge-admin-$VERSION-runner.jar" ]] || die "Admin runner missing."
}
backup(){
  local stamp; stamp="$(date +%Y%m%d-%H%M%S)"
  tar -czf "$BACKUPS/pre-${ACTION}-v${VERSION}-${stamp}.tar.gz" "$ROOT" "$ETC" 2>/dev/null || true
  log "Backup: $BACKUPS/pre-${ACTION}-v${VERSION}-${stamp}.tar.gz"
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
start_and_check(){
  systemctl enable --now nord-forge.service nord-forge-admin.service
  local ok=0
  for _ in $(seq 1 30); do
    if curl -fsS http://127.0.0.1:8088/q/health/ready >/dev/null && curl -fsS http://127.0.0.1:8089/q/health/ready >/dev/null; then ok=1; break; fi
    sleep 1
  done
  [[ "$ok" -eq 1 ]] || { journalctl -u nord-forge -n 80 --no-pager || true; journalctl -u nord-forge-admin -n 80 --no-pager || true; die "Health verification failed."; }
  log "Nord Forge V$VERSION is healthy."
}

case "$ACTION" in
  install)
    install_deps; ensure_user; ensure_env; build; backup; install_files; start_and_check
    ;;
  update)
    install_deps; ensure_user; ensure_env; build; backup
    systemctl stop nord-forge-admin.service nord-forge.service 2>/dev/null || true
    install_files
    if ! start_and_check; then die "Update failed. Restore the latest archive in $BACKUPS."; fi
    ;;
  uninstall)
    systemctl disable --now nord-forge-admin.service nord-forge.service 2>/dev/null || true
    rm -f /etc/systemd/system/nord-forge.service /etc/systemd/system/nord-forge-admin.service /usr/local/bin/nord-forge
    systemctl daemon-reload
    log "Services removed. Persistent data remains at $DATA."
    ;;
  *) die "Usage: install.sh [install|update|uninstall]";;
esac
