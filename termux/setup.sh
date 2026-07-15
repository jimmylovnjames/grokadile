#!/data/data/com.termux/files/usr/bin/bash
# Grokadile v0.1 Termux bootstrap
# Idempotent. Run once after cloning/copying files to ~/grokadile

set -e

echo "[Grokadile] Updating packages..."
pkg update -y
pkg install -y python git termux-api 2>/dev/null || true

echo "[Grokadile] Installing Python deps..."
pip install --upgrade pip
pip install requests

echo "[Grokadile] Ensuring directory structure..."
mkdir -p ~/grokadile/state ~/grokadile/logs ~/grokadile/tools

echo "[Grokadile] Making scripts executable..."
chmod +x ~/grokadile/setup.sh 2>/dev/null || true
chmod +x ~/grokadile/grokadile.py 2>/dev/null || true

echo "[Grokadile] Bootstrap complete."
echo "Next:"
echo "  export GROK_API_KEY='sk-...'   # or XAI_API_KEY"
echo "  export GROK_MODEL='grok-4.5'"
echo "  python ~/grokadile/grokadile.py --help"
echo "  python ~/grokadile/grokadile.py --demo --goal 'test task'"
echo ""
echo "For persistent agents add to ~/.bashrc or use termux-services."