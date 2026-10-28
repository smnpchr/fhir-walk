#!/usr/bin/env bash
# Build fhir-walk and install it system-wide, replacing any earlier installation.
#
#   ./install.sh                 app in /usr/local/lib/fhir-walk, command in /usr/local/bin
#   ./install.sh --opt           app in /opt/fhir-walk, command in /usr/local/bin
#   ./install.sh --prefix DIR    app in DIR/lib/fhir-walk, command in DIR/bin
#   ./install.sh --uninstall     remove whichever installation is found
#
# Session data -- expressions, names, views, saved state, line history -- lives in
# each user's ~/.fhir-walk, never under the install prefix. This script does not
# read, move or delete it, so an upgrade or uninstall leaves every workspace as it was.
#
# Run it as yourself: the build runs unprivileged and only the copy asks for sudo.
set -euo pipefail

APP=fhir-walk
SRC=$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd)

# ---------------------------------------------------------------- presentation

if [[ -t 1 && -z ${NO_COLOR:-} ]]; then
    B=$'\e[1m' D=$'\e[2m' R=$'\e[31m' G=$'\e[32m' Y=$'\e[33m' C=$'\e[36m' N=$'\e[0m'
else
    B='' D='' R='' G='' Y='' C='' N=''
fi

step() { printf '%s==>%s %s%s%s\n' "$C" "$N" "$B" "$*" "$N"; }
info() { printf '    %s\n' "$*"; }
ok()   { printf '    %s✓%s %s\n' "$G" "$N" "$*"; }
warn() { printf '    %s!%s %s\n' "$Y" "$N" "$*" >&2; }
die()  { printf '%s✗ %s%s\n' "$R" "$*" "$N" >&2; exit 1; }

# Run a long command behind a spinner; show its log only if it fails.
spin() {
    local label=$1 log; shift
    log=$(mktemp "${TMPDIR:-/tmp}/$APP-install.XXXXXX")
    "$@" >"$log" 2>&1 &
    local pid=$! frames='⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏' i=0
    if [[ -t 1 ]]; then
        while kill -0 "$pid" 2>/dev/null; do
            printf '\r    %s%s%s %s' "$C" "${frames:i++%${#frames}:1}" "$N" "$label"
            sleep 0.1
        done
        printf '\r\e[K'
    fi
    if wait "$pid"; then
        rm -f "$log"; ok "$label"
    else
        printf '    %s✗%s %s\n' "$R" "$N" "$label" >&2
        tail -n 40 "$log" >&2
        die "failed; full log in $log"
    fi
}

usage() {
    sed -n '2,13s/^# \{0,1\}//p' "${BASH_SOURCE[0]}"
    cat <<EOF

options:
  --prefix DIR   install under DIR (default /usr/local)
  --opt          self-contained in /opt/$APP, linked from /usr/local/bin
  --no-build     install what build/install/$APP already holds
  --force        replace a $APP command that is not one of these installs
  --uninstall    remove the installation; session data stays
  -h, --help     this text
EOF
}

# ---------------------------------------------------------------- arguments

prefix=/usr/local layout=prefix build=1 force=0 action=install
while (($#)); do
    case $1 in
        --prefix)    [[ -n ${2:-} ]] || die "--prefix needs a directory"; prefix=${2%/}; shift ;;
        --prefix=*)  prefix=${1#*=}; prefix=${prefix%/} ;;
        --opt)       layout=opt ;;
        --no-build)  build=0 ;;
        --force)     force=1 ;;
        --uninstall) action=uninstall ;;
        -h|--help)   usage; exit 0 ;;
        *)           die "unknown option '$1' -- try --help" ;;
    esac
    shift
done

if [[ $layout == opt ]]; then
    app_dir=/opt/$APP bin_dir=/usr/local/bin
else
    app_dir=$prefix/lib/$APP bin_dir=$prefix/bin
fi
link=$bin_dir/$APP

# An install is known by where it is. Moving between the two standard layouts
# replaces the other one; a custom --prefix only ever replaces itself.
known=("$app_dir")
[[ $app_dir == /usr/local/lib/$APP || $app_dir == /opt/$APP ]] &&
    known=(/usr/local/lib/$APP /opt/$APP)
links=("$link")
[[ $link != /usr/local/bin/$APP ]] || links=(/usr/local/bin/$APP)

# The user whose session data we report on: the one behind sudo, if any.
owner=${SUDO_USER:-$(id -un)}
owner_home=$(getent passwd "$owner" | cut -d: -f6)
data_dir=${owner_home:-$HOME}/.fhir-walk

# sudo only where the target is not writable by us.
needs_root() {
    local d
    for d in "$@"; do
        while [[ ! -e $d ]]; do d=$(dirname "$d"); done
        [[ -w $d ]] || return 0
    done
    return 1
}
priv() {
    if [[ $EUID -ne 0 ]] && needs_root "${known[@]}" "$bin_dir"; then sudo "$@"; else "$@"; fi
}

# The version an install holds, read from its main jar's name.
version_of() {
    local jar
    jar=$(find "$1/lib" -maxdepth 1 -name "$APP-[0-9]*.jar" 2>/dev/null | head -n 1)
    jar=${jar##*/$APP-}
    printf '%s' "${jar%.jar}"
}

# Does a link point into one of the known install directories?
ours() {
    local target d
    target=$(readlink -f "$1" 2>/dev/null) || return 1
    for d in "${known[@]}"; do [[ $target == "$d"/* ]] && return 0; done
    return 1
}

report_data() {
    if [[ -d $data_dir ]]; then
        local n=0
        [[ -d $data_dir/workspaces ]] && n=$(find "$data_dir/workspaces" -mindepth 1 -maxdepth 1 -type d | wc -l)
        ok "session data untouched: $data_dir ($((n + 1)) workspace$([[ $n -gt 0 ]] && echo s))"
    else
        info "${D}no session data yet; it will be kept in $data_dir${N}"
    fi
}

# ---------------------------------------------------------------- uninstall

if [[ $action == uninstall ]]; then
    printf '\n%s%s uninstall%s\n\n' "$B" "$APP" "$N"
    found=0
    for l in "${links[@]}"; do
        if [[ -L $l ]] && { ours "$l" || [[ ! -e $l ]]; }; then
            priv rm -f "$l" && ok "removed $l"
        fi
    done
    for d in "${known[@]}"; do
        [[ -d $d ]] || continue
        found=1
        v=$(version_of "$d" || true)
        priv rm -rf "$d" && ok "removed $d ${v:+(${B}$v${N})}"
    done
    ((found)) || warn "no installation found in ${known[*]}"
    report_data
    echo
    exit 0
fi

# ---------------------------------------------------------------- install

version=$(sed -n 's/^version = "\(.*\)"/\1/p' "$SRC/build.gradle.kts")
commit=$(git -C "$SRC" describe --always --dirty 2>/dev/null || echo unknown)

printf '\n%s%s %s%s %s(%s)%s\n\n' "$B" "$APP" "$version" "$N" "$D" "$commit" "$N"

step "Checking requirements"
java=${JAVA_HOME:+$JAVA_HOME/bin/}java
if command -v "$java" >/dev/null; then
    jv=$("$java" -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')
    if [[ ${jv:-0} -ge 21 ]]; then ok "Java $jv"
    else warn "Java ${jv:-?} found; $APP needs 21 or newer at run time"
    fi
else
    warn "no java on PATH or JAVA_HOME; $APP needs Java 21 or newer at run time"
fi
# A dangling link is replaced without asking; anything else that is not ours is kept.
if [[ -e $link ]] && ! ours "$link" && ((!force)); then
    die "$link is not one of these installs ($(readlink -f "$link")); move it away or pass --force"
fi
ok "target: $app_dir"
ok "command: $link"

dist=$SRC/build/install/$APP
if ((build)); then
    step "Building"
    gradle=("$SRC/gradlew" -p "$SRC" -q --console=plain installDist)
    # Never build as root: it would leave root-owned files in the checkout.
    if [[ $EUID -eq 0 && -n ${SUDO_USER:-} ]]; then gradle=(sudo -u "$SUDO_USER" "${gradle[@]}"); fi
    spin "gradle installDist" "${gradle[@]}"
fi
[[ -x $dist/bin/$APP ]] || die "no build in $dist; run without --no-build"
# Checked before anything is deleted, so a broken build never costs a working install.
"$dist/bin/$APP" --help >/dev/null 2>&1 || die "the build in $dist does not start (tried --help)"
ok "$(du -sh "$dist" | cut -f1) in $(find "$dist/lib" -name '*.jar' | wc -l) jars, starts"

previous=()
for d in "${known[@]}"; do [[ -d $d ]] && previous+=("$d"); done
if ((${#previous[@]})); then
    step "Replacing earlier installation"
    for d in "${previous[@]}"; do info "$d: ${B}$(version_of "$d" || true)${N}"; done
else
    step "Installing"
fi
if [[ $EUID -ne 0 ]] && needs_root "${known[@]}" "$bin_dir"; then
    info "${D}sudo is needed to write ${app_dir%/*} and $bin_dir${N}"
fi

for d in "${previous[@]}"; do priv rm -rf "$d" && ok "removed $d"; done
priv mkdir -p "${app_dir%/*}" "$bin_dir"
priv cp -R "$dist" "$app_dir"
priv rm -f "$app_dir/bin/$APP.bat"
priv chmod -R u=rwX,go=rX "$app_dir"
priv ln -sfn "$app_dir/bin/$APP" "$link"
ok "installed $app_dir"
ok "linked $link"

report_data

case ":$PATH:" in
    *":$bin_dir:"*) ;;
    *) warn "$bin_dir is not on your PATH" ;;
esac
resolved=$(command -v "$APP" 2>/dev/null || true)
if [[ -n $resolved && $resolved != "$link" ]]; then
    warn "'$APP' on your PATH is $resolved, which shadows $link"
fi

printf '\n%sDone.%s Run %s%s <path>%s — %s--help%s for options.\n\n' "$G$B" "$N" "$B" "$APP" "$N" "$B" "$N"
