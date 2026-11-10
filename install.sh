#!/usr/bin/env bash
# QwenNodeAgent kurulumu — Termux PRoot Ubuntu (arm64) veya doğrudan Termux.
# Tek komut:
#   curl -fsSL https://raw.githubusercontent.com/SemihArin/ilker/main/install.sh | bash
set -euo pipefail

MODEL="${QWEN_MODEL:-qwen2.5:7b}"
RAW_BASE="${QWEN_RAW_BASE:-https://raw.githubusercontent.com/SemihArin/ilker/main}"
INSTALL_DIR="${QWEN_INSTALL_DIR:-$HOME/.qwen-node-agent}"

say() { printf '\033[1;32m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31mHATA:\033[0m %s\n' "$*" >&2; exit 1; }
node_ok() { command -v node >/dev/null 2>&1 && [ "$(node -p 'process.versions.node.split(".")[0]')" -ge 18 ]; }

# --- 1) Paketler: curl, Node.js 18+, Ollama --------------------------------
if [ -n "${TERMUX_VERSION:-}" ]; then
  # Doğrudan Termux (PRoot dışı)
  BIN_DIR="$PREFIX/bin"
  say "Termux paketleri kuruluyor"
  pkg update -y
  pkg install -y curl nodejs-lts ollama
else
  # PRoot Ubuntu / Debian
  command -v apt-get >/dev/null 2>&1 || die "Desteklenmeyen ortam (apt-get veya Termux gerekli)."
  SUDO=""
  [ "$(id -u)" -eq 0 ] || SUDO="sudo"
  BIN_DIR="/usr/local/bin"
  export DEBIAN_FRONTEND=noninteractive

  say "Sistem paketleri kuruluyor"
  $SUDO apt-get update -y
  $SUDO apt-get install -y curl ca-certificates zstd

  if ! node_ok; then
    say "Node.js kuruluyor"
    $SUDO apt-get install -y nodejs || true
    if ! node_ok; then
      curl -fsSL https://deb.nodesource.com/setup_20.x | $SUDO bash -
      $SUDO apt-get install -y nodejs
    fi
  fi

  if ! command -v ollama >/dev/null 2>&1; then
    say "Ollama kuruluyor"
    curl -fsSL https://ollama.com/install.sh | sh
  fi
fi
node_ok || die "Node.js 18+ kurulamadı."
command -v ollama >/dev/null 2>&1 || die "Ollama kurulamadı."

# --- 2) Ajan dosyası --------------------------------------------------------
say "Ajan $INSTALL_DIR dizinine kuruluyor"
mkdir -p "$INSTALL_DIR"
SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-}")" 2>/dev/null && pwd || true)"
if [ -n "$SRC_DIR" ] && [ -f "$SRC_DIR/agent.js" ]; then
  cp "$SRC_DIR/agent.js" "$INSTALL_DIR/agent.js"   # klonlanmış depodan
else
  curl -fsSL "$RAW_BASE/agent.js" -o "$INSTALL_DIR/agent.js"
fi

# --- 3) Başlatıcı: Ollama kapalıysa açar, sonra ajanı çalıştırır -------------
LAUNCHER="$INSTALL_DIR/qwen-agent"
cat > "$LAUNCHER" <<EOF
#!/usr/bin/env bash
if ! curl -fs http://127.0.0.1:11434/api/version >/dev/null 2>&1; then
  nohup ollama serve >"$INSTALL_DIR/ollama.log" 2>&1 &
  for _ in \$(seq 1 30); do
    curl -fs http://127.0.0.1:11434/api/version >/dev/null 2>&1 && break
    sleep 1
  done
fi
exec node "$INSTALL_DIR/agent.js" "\$@"
EOF
chmod +x "$LAUNCHER"
if [ -n "${TERMUX_VERSION:-}" ]; then
  ln -sf "$LAUNCHER" "$BIN_DIR/qwen-agent"
else
  $SUDO ln -sf "$LAUNCHER" "$BIN_DIR/qwen-agent"
fi

# --- 4) Model ---------------------------------------------------------------
if ! curl -fs http://127.0.0.1:11434/api/version >/dev/null 2>&1; then
  say "Ollama sunucusu başlatılıyor"
  nohup ollama serve >"$INSTALL_DIR/ollama.log" 2>&1 &
  for _ in $(seq 1 30); do
    curl -fs http://127.0.0.1:11434/api/version >/dev/null 2>&1 && break
    sleep 1
  done
fi
curl -fs http://127.0.0.1:11434/api/version >/dev/null 2>&1 \
  || die "Ollama sunucusu başlamadı. Günlük: $INSTALL_DIR/ollama.log"

say "$MODEL indiriliyor (~4.7 GB, bir kez)"
ollama pull "$MODEL"

say "Kurulum tamam. Proje dizininizde şunu çalıştırın:  qwen-agent"
