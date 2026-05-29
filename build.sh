#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DOMAIN_DEFAULT="aisocialgame.localhut.com"
APP_DOMAIN="${APP_DOMAIN:-$APP_DOMAIN_DEFAULT}"

APP_DOMAIN="$APP_DOMAIN" APP_DOMAIN_DEFAULT="$APP_DOMAIN_DEFAULT" "$repo_root/build_common.sh"
