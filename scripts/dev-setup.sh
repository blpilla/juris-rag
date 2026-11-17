#!/usr/bin/env sh
set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
cd "$PROJECT_DIR"
python3 scripts/generate_fixtures.py
python3 scripts/generate_dev_keys.py
if [ ! -f .env ]; then
  cp .env.example .env
fi
echo "Configuração local pronta. .env e .local/ não devem ser enviados ao Git."
