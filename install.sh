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

# Bash's strict mode: -e stops at the first failing command, -u treats an unset
# variable as an error rather than an empty string, and pipefail makes a pipeline
# fail when any part of it fails, not only the last. One catch: a command on the
# left of && or || is exempt from -e, so "rm x && ok" fails silently. Hence the
# "cmd || die" pattern below.
set -euo pipefail

APP=fhir-walk
# The script's own directory, wherever it is called from. BASH_SOURCE[0] is this
# file's path; cd -P resolves symlinks, and pwd prints the result.
SRC=$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd)

# ---------------------------------------------------------------- presentation

# Colour only on a terminal: -t 1 asks whether stdout is one, so a pipe or log
# file gets plain text. NO_COLOR is a common convention for opting out. $'...'
# lets \e stand for the escape character that starts an ANSI code.
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
    # shift drops the label, so "$@" is left holding the command and its arguments.
    local label=$1 log; shift
    log=$(mktemp "${TMPDIR:-/tmp}/$APP-install.XXXXXX")
    # A trailing & runs the command in the background; $! is its process id.
    "$@" >"$log" 2>&1 &
    local pid=$! frames='⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏' i=0
    if [[ -t 1 ]]; then
        # kill -0 sends no signal; it only asks whether the process still exists.
        while kill -0 "$pid" 2>/dev/null; do
            # \r goes back to the start of the line, so each frame overwrites the
            # last. ${frames:offset:1} picks one character, cycling with the modulo.
            printf '\r    %s%s%s %s' "$C" "${frames:i++%${#frames}:1}" "$N" "$label"
            sleep 0.1
        done
        # \e[K clears the rest of the line, for the ✓ line to take its place.
        printf '\r\e[K'
    fi
    # wait returns the background command's exit status.
    if wait "$pid"; then
        rm -f "$log"; ok "$label"
    else
        printf '    %s✗%s %s\n' "$R" "$N" "$label" >&2
        tail -n 40 "$log" >&2
        die "failed; full log in $log"
    fi
}

usage() {
    # The help text is the header comment above: sed prints lines 2-13 of this
    # file with their "# " stripped, so the two can never drift apart.
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

# The usual option loop: $# counts the remaining arguments, case matches the
# first, and shift drops it. An option with a value shifts once more.
prefix=/usr/local prefix_given=0 layout=prefix build=1 force=0 action=install
while (($#)); do
    case $1 in
        --prefix)    (($# > 1)) || die "--prefix needs a directory"; prefix=$2 prefix_given=1; shift ;;
        --prefix=*)  prefix=${1#*=} prefix_given=1 ;;
        --opt)       layout=opt ;;
        --no-build)  build=0 ;;
        --force)     force=1 ;;
        --uninstall) action=uninstall ;;
        -h|--help)   usage; exit 0 ;;
        *)           die "unknown option '$1' -- try --help" ;;
    esac
    shift
done

# The user behind sudo, if any: whose home ~ and the session data are.
owner=${SUDO_USER:-$(id -un)}
# getent reads the user database (field 6 is the home directory). "|| true"
# keeps a failed lookup from ending the script; ${var:-default} fills the gap.
owner_home=$(getent passwd "$owner" 2>/dev/null | cut -d: -f6) || true
owner_home=${owner_home:-$HOME}
data_dir=$owner_home/.fhir-walk

[[ $prefix_given == 1 && $layout == opt ]] && die "--opt and --prefix exclude each other"
[[ -n $prefix ]] || die "--prefix needs a directory"
# Bash expands ~ only at the start of a word, not after "--prefix=", so it is
# done by hand. ${prefix#"~"} is the value with the leading ~ removed.
[[ $prefix == "~" || $prefix == "~/"* ]] && prefix=$owner_home${prefix#"~"}
# Absolute and normalised, so the link resolves and the standard layouts compare
# equal. -m allows a path that does not exist yet; -- ends the options, in case
# the path starts with a dash.
prefix=$(realpath -m -- "$prefix")
[[ $prefix != / ]] || die "--prefix / would install into /lib and /bin, which belong to the system"

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

# sudo only where we cannot write. Removing or creating a directory takes its
# parent, and removing an install takes the install itself; decided once, up
# front, because the answer changes as soon as an old copy is gone.
writable() {
    local d=$1
    while [[ ! -e $d ]]; do d=$(dirname "$d"); done
    [[ -w $d ]]
}
use_sudo=0
if [[ $EUID -ne 0 ]]; then
    # "${known[@]%/*}" applies %/* (drop the last path step) to every element,
    # giving each install directory's parent.
    for d in "${known[@]}" "${known[@]%/*}" "$bin_dir"; do
        writable "$d" || use_sudo=1
    done
fi
# A wrapper that runs its arguments as a command, with or without sudo. sudo can
# only run programs, not shell functions, so priv is only ever given programs.
priv() { if ((use_sudo)); then sudo "$@"; else "$@"; fi; }

# The version an install holds, read from its main jar's name.
version_of() {
    local jar
    jar=$(find "$1/lib" -maxdepth 1 -name "$APP-[0-9]*.jar" 2>/dev/null | head -n 1)
    # ## removes the longest match from the front, % the shortest from the end:
    # /…/lib/fhir-walk-0.1.0.jar becomes 0.1.0.
    jar=${jar##*/$APP-}
    printf '%s' "${jar%.jar}"
}

# Does a link point into one of the known install directories?
ours() {
    local target d
    # readlink -f follows every link in the chain to the final absolute path.
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
    if [[ -L $link ]] && { ours "$link" || [[ ! -e $link ]]; }; then
        priv rm -f "$link" || die "could not remove $link"
        ok "removed $link"
    fi
    for d in "${known[@]}"; do
        [[ -d $d ]] || continue
        found=1
        v=$(version_of "$d" || true)
        priv rm -rf "$d" || die "could not remove $d"
        ok "removed $d ${v:+(${B}$v${N})}"
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
    # As root, build as the checkout's owner, or the build leaves root-owned files
    # in it. runuser keeps the environment (JAVA_HOME, PATH), which sudo -u resets.
    # The command is kept in an array so arguments with spaces survive, and a
    # prefix like runuser can be put in front without re-quoting anything.
    builder=$(stat -c %U "$SRC")
    if [[ $EUID -eq 0 && $builder != root ]]; then
        if command -v runuser >/dev/null; then gradle=(runuser -u "$builder" -- "${gradle[@]}")
        else gradle=(sudo -E -u "$builder" "${gradle[@]}")
        fi
    fi
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
((use_sudo)) && info "${D}sudo is needed to write ${app_dir%/*} and $bin_dir${N}"

for d in "${previous[@]}"; do
    priv rm -rf "$d" || die "could not remove $d"
    ok "removed $d"
done
# cp -R into an existing directory would nest the copy one level down.
[[ ! -e $app_dir ]] || die "$app_dir is still there; not copying into it"
# Braces group the chain, so one "|| die" covers whichever step fails first.
{ priv mkdir -p "${app_dir%/*}" "$bin_dir" &&
  priv cp -R "$dist" "$app_dir" &&
  priv rm -f "$app_dir/bin/$APP.bat" &&
  priv chmod -R u=rwX,go=rX "$app_dir"; } || die "could not copy to $app_dir; run again to retry"
ok "installed $app_dir"
# -s symbolic, -f replace what is there, -n treat an existing link to a
# directory as the link itself rather than descending into it.
priv ln -sfn "$app_dir/bin/$APP" "$link" || die "could not link $link"
ok "linked $link"

report_data

# Under sudo or su, PATH is root's, not the one the command will be run from.
if [[ $EUID -ne 0 ]]; then
    case ":$PATH:" in
        *":$bin_dir:"*) ;;
        *) warn "$bin_dir is not on your PATH" ;;
    esac
    resolved=$(command -v "$APP" 2>/dev/null || true)
    if [[ -n $resolved && $resolved != "$link" ]]; then
        warn "'$APP' on your PATH is $resolved, which shadows $link"
    fi
fi

printf '\n%sDone.%s Run %s%s <path>%s — %s--help%s for options.\n\n' "$G$B" "$N" "$B" "$APP" "$N" "$B" "$N"
