#!/usr/bin/env bash
# Installs M3U Player on a Debian/Ubuntu VPS: the app, its built-in proxy,
# and automatic HTTPS (Caddy + Let's Encrypt). Safe to run again to update.
#
#   git clone https://github.com/wmorgan97/m3u-player.git
#   sudo bash m3u-player/deploy/install.sh [your.domain.com]
#
# Without a domain it uses a free <ip>.sslip.io address.
set -euo pipefail

[ "$(id -u)" = 0 ] || { echo "Run with sudo: sudo bash $0 $*"; exit 1; }
APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT=8787
ENV_FILE=/etc/m3u-player.env
MARK="# m3u-player"

say() { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }

say "Installing prerequisites"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq curl ca-certificates gnupg debian-keyring debian-archive-keyring apt-transport-https >/dev/null

if ! command -v node >/dev/null || [ "$(node -p 'process.versions.node.split(".")[0]')" -lt 18 ]; then
  say "Installing Node.js 22"
  curl -fsSL https://deb.nodesource.com/setup_22.x | bash - >/dev/null
  apt-get install -y -qq nodejs >/dev/null
fi

# Domain: argument, or <public-ip>.sslip.io (a free wildcard DNS that HTTPS certificates work with).
DOMAIN="${1:-}"
if [ -z "$DOMAIN" ]; then
  IP="$(curl -fsS4 --max-time 10 https://api.ipify.org || curl -fsS4 --max-time 10 https://ifconfig.me)"
  DOMAIN="${IP//./-}.sslip.io"
fi

# Proxy key: keep the existing one on re-runs.
if [ -f "$ENV_FILE" ] && grep -q '^PROXY_KEY=' "$ENV_FILE"; then
  KEY="$(grep '^PROXY_KEY=' "$ENV_FILE" | cut -d= -f2-)"
else
  KEY="$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 20)"
fi
printf 'PORT=%s\nHOST=127.0.0.1\nPROXY_KEY=%s\n' "$PORT" "$KEY" > "$ENV_FILE"
chmod 600 "$ENV_FILE"

say "Setting up the m3u-player service"
cat > /etc/systemd/system/m3u-player.service <<EOF
[Unit]
Description=M3U Player (app + proxy)
After=network-online.target
Wants=network-online.target

[Service]
EnvironmentFile=$ENV_FILE
ExecStart=$(command -v node) $APP_DIR/server/server.js
Restart=always
RestartSec=3
DynamicUser=yes
ProtectSystem=strict
ProtectHome=read-only
NoNewPrivileges=yes

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now m3u-player >/dev/null
systemctl restart m3u-player

# HTTPS front end. If another web server already owns ports 80/443, don't touch it.
busy="$(ss -ltnpH '( sport = :80 or sport = :443 )' 2>/dev/null | grep -v caddy || true)"
if [ -n "$busy" ] && ! command -v caddy >/dev/null; then
  say "Ports 80/443 are already used by another web server, so Caddy was not installed."
  echo "Point your existing web server (nginx, Apache…) at http://127.0.0.1:$PORT for $DOMAIN, with HTTPS."
  echo "nginx example:  location / { proxy_pass http://127.0.0.1:$PORT; proxy_buffering off; }"
else
  if ! command -v caddy >/dev/null; then
    say "Installing Caddy (automatic HTTPS)"
    curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
    curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' > /etc/apt/sources.list.d/caddy-stable.list
    apt-get update -qq
    apt-get install -y -qq caddy >/dev/null
  fi
  CADDYFILE=/etc/caddy/Caddyfile
  # Replace our block (between markers) and keep anything else that's in the file.
  touch "$CADDYFILE"
  sed -i "/^$MARK start/,/^$MARK end/d" "$CADDYFILE"
  # The stock Caddyfile serves a placeholder page on :80, which would stop the
  # HTTP→HTTPS redirect for our domain. Remove that block (a backup is kept).
  if grep -q '^:80 {' "$CADDYFILE" && grep -q 'root \* /usr/share/caddy' "$CADDYFILE"; then
    cp "$CADDYFILE" "$CADDYFILE.bak"
    sed -i '/^:80 {/,/^}/d' "$CADDYFILE"
  fi
  cat >> "$CADDYFILE" <<EOF
$MARK start
$DOMAIN {
	encode gzip
	reverse_proxy 127.0.0.1:$PORT {
		flush_interval -1
	}
}
$MARK end
EOF
  caddy validate --config "$CADDYFILE" --adapter caddyfile >/dev/null
  systemctl enable --now caddy >/dev/null
  systemctl reload caddy
fi

if command -v ufw >/dev/null && ufw status | grep -q 'Status: active'; then
  ufw allow 80/tcp >/dev/null; ufw allow 443/tcp >/dev/null
fi

sleep 2
say "Done"
cat <<EOF

  App:        https://$DOMAIN
  Server key: $KEY

  On your iPhone:
    1. Open https://$DOMAIN in Safari (the first load can take ~30s while
       the HTTPS certificate is issued).
    2. Share → Add to Home Screen, then open the app from your Home Screen.
    3. It asks for the server key once. Enter: $KEY

  Update later:  cd $APP_DIR && git pull && sudo systemctl restart m3u-player
  Logs:          journalctl -u m3u-player -f
  Make sure your VPS firewall / provider panel allows ports 80 and 443.
EOF
