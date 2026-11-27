#!/usr/bin/env bash
# Type-checks the :app module (both distribution flavors) without Gradle or the Android SDK.
# This is a sandbox/CI fallback; `./gradlew :app:assemble` remains the source of truth.
#
# The Kotlin sources compile against:
#   - the real platform android.jar ($ANDROID_JAR)
#   - the compiled :core classes ($CORE_CLASSES, produced by tools/verify-core.sh)
#   - the real Java sources of Capacitor ($CAPACITOR_JAVA), androidx.webkit ($WEBKIT_JAVA) and
#     sqlcipher-android ($SQLCIPHER_JAVA); kotlinc resolves symbols from them without compiling them
#   - generated stand-ins for build outputs: R (from res/values), BuildConfig (per flavor), AIDL
#
# Then it checks that every component declared in AndroidManifest.xml exists as a compiled class.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"   # apps/shell/android
APP="$ROOT/app/src"
KOTLINC="${KOTLINC:-kotlinc}"
OUT="${OUT_DIR:-$(mktemp -d)}"
: "${ANDROID_JAR:?set ANDROID_JAR}" "${CORE_CLASSES:?set CORE_CLASSES (run verify-core.sh with OUT_DIR, use \$OUT_DIR/classes)}"
: "${CAPACITOR_JAVA:=$ROOT/../../../node_modules/@capacitor/android/capacitor/src/main/java}"
: "${WEBKIT_JAVA:?set WEBKIT_JAVA (androidx/webkit/webkit/src/main/java)}" "${SQLCIPHER_JAVA:?set SQLCIPHER_JAVA (sqlcipher-android/sqlcipher/src/main/java)}"

gen_stubs() { # $1 = dir, $2 = A11Y_ACTIONS value
  python3 - "$1" "$2" "$APP" <<'PY'
import os, re, sys, xml.etree.ElementTree as ET
out, a11y, app = sys.argv[1], sys.argv[2], sys.argv[3]
pkg = os.path.join(out, "com/agentkeyboard/app"); os.makedirs(os.path.join(pkg, "agent"), exist_ok=True)
open(os.path.join(pkg, "BuildConfig.java"), "w").write(
  "package com.agentkeyboard.app;\npublic final class BuildConfig {\n"
  "  public static final boolean DEBUG = false;\n  public static final String APPLICATION_ID = \"com.agentkeyboard.app\";\n"
  f"  public static final boolean A11Y_ACTIONS = {a11y};\n}}\n")
kinds = {}
for f in sorted(os.listdir(os.path.join(app, "main/res/values"))):
  for el in ET.parse(os.path.join(app, "main/res/values", f)).getroot():
    if el.get("name"): kinds.setdefault(el.tag, set()).add(el.get("name").replace(".", "_"))
for d in os.listdir(os.path.join(app, "main/res")):
  base = d.split("-")[0]
  if base in ("xml", "drawable", "layout", "mipmap"):
    for f in os.listdir(os.path.join(app, "main/res", d)):
      kinds.setdefault(base, set()).add(f.split(".")[0])
body = "".join(
  f"  public static final class {k} {{\n" + "".join(f"    public static final int {n} = {i};\n" for i, n in enumerate(sorted(v), 1)) + "  }\n"
  for k, v in sorted(kinds.items()))
open(os.path.join(pkg, "R.java"), "w").write(f"package com.agentkeyboard.app;\npublic final class R {{\n{body}}}\n")
aidl = os.path.join(app, "main/aidl/com/agentkeyboard/app/agent")
for f in os.listdir(aidl):
  name = f[:-5]; src = open(os.path.join(aidl, f)).read()
  src = re.sub(r"/\*.*?\*/", "", src, flags=re.S); src = re.sub(r"//.*", "", src)
  body = src[src.index("{") + 1: src.rindex("}")]
  sigs = "".join(f"  {m.strip().replace('oneway ', '')} throws android.os.RemoteException;\n" for m in body.split(";") if m.strip())
  open(os.path.join(pkg, "agent", name + ".java"), "w").write(
    f"package com.agentkeyboard.app.agent;\npublic interface {name} extends android.os.IInterface {{\n{sigs}"
    f"  public static abstract class Stub extends android.os.Binder implements {name} {{\n"
    f"    public static {name} asInterface(android.os.IBinder b) {{ return null; }}\n"
    "    @Override public android.os.IBinder asBinder() { return this; }\n  }\n}\n")
PY
}

status=0
for flavor in play full; do
  a11y=false; [ "$flavor" = full ] && a11y=true
  stubs="$OUT/$flavor/stubs"; classes="$OUT/$flavor/classes"
  rm -rf "$OUT/$flavor"; mkdir -p "$stubs"; cp -r "$HERE/app-stubs/androidx" "$stubs/"
  gen_stubs "$stubs" "$a11y"
  mapfile -t KT < <(find "$APP/main/java" "$APP/$flavor/java" -name '*.kt' 2>/dev/null)
  echo "▸ $flavor: compiling ${#KT[@]} Kotlin files"
  log="$OUT/$flavor/kotlinc.log"
  if "$KOTLINC" -jvm-target 17 -Werror -cp "$ANDROID_JAR:$CORE_CLASSES" -d "$classes" \
      "${KT[@]}" "$stubs" "$CAPACITOR_JAVA" "$WEBKIT_JAVA" "$SQLCIPHER_JAVA" >"$log" 2>&1; then
    echo "  ok (0 errors, 0 warnings)"
  else
    grep -E "error:|warning:" "$log" | sed "s#$ROOT/##" | head -50; status=1; continue
  fi
  python3 - "$APP/main/AndroidManifest.xml" "$classes" <<'PY' || status=1
import os, sys, xml.etree.ElementTree as ET
A = "{http://schemas.android.com/apk/res/android}"
root = ET.parse(sys.argv[1]).getroot(); missing = []; n = 0
app = root.find("application")
for el in [app] + [c for c in app if c.tag in ("activity", "service", "receiver", "provider")]:
  name = el.get(A + "name")
  if not name or not (name.startswith(".") or name.startswith("com.agentkeyboard.app")): continue
  fq = ("com.agentkeyboard.app" + name) if name.startswith(".") else name
  n += 1
  if not os.path.exists(os.path.join(sys.argv[2], fq.replace(".", "/") + ".class")): missing.append(fq)
print(f"  manifest: {n} own components, {len(missing)} missing" + (": " + ", ".join(missing) if missing else ""))
sys.exit(1 if missing else 0)
PY
done
exit $status
