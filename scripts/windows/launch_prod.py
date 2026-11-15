"""Launch a PRODUCTION Fabric 26.3 client (no Loom dev environment) from the official launcher's files, with its own game directory.
   python -I launch_prod.py <gameDir> [--script "<FSR4_SCRIPT>"] [--width W --height H]
Uses .minecraft/versions/fabric-loader-0.19.5-26.3 (inherits 26.3), .minecraft/libraries and .minecraft/assets. Offline user, no account
token: singleplayer only. Used to verify that the SugarSV all-in-one jar works as a normal mods/ install."""
import json, os, subprocess, sys, tempfile, argparse

MC = os.path.join(os.environ["APPDATA"], ".minecraft")
VER = "fabric-loader-0.19.5-26.3"
JAVA = r"C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot\bin\java.exe"

def lib_path(lib):
    a = lib.get("downloads", {}).get("artifact")
    if a and a.get("path"): return os.path.join(MC, "libraries", a["path"])
    g, n, v = lib["name"].split(":")[:3]
    return os.path.join(MC, "libraries", *g.split("."), n, v, f"{n}-{v}.jar")

def allowed(lib):
    rules = lib.get("rules"); ok = not rules
    for r in rules or []:
        osr = r.get("os")
        if osr and osr.get("name") not in (None, "windows"): continue
        if osr and osr.get("arch") and osr["arch"] != "x86": pass
        ok = r["action"] == "allow"
    return ok

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("gamedir"); ap.add_argument("--script"); ap.add_argument("--width", default="1920"); ap.add_argument("--height", default="1080"); ap.add_argument("--log"); ap.add_argument("--libcopy")
    a = ap.parse_args()
    fab = json.load(open(os.path.join(MC, "versions", VER, VER + ".json"), encoding="utf-8"))
    van = json.load(open(os.path.join(MC, "versions", fab["inheritsFrom"], fab["inheritsFrom"] + ".json"), encoding="utf-8"))
    cp, seen, missing = [], set(), []
    for lib in fab["libraries"] + van["libraries"]:   # Fabric's libraries first: they win over vanilla's (asm etc.)
        if not allowed(lib): continue
        key = ":".join(lib["name"].split(":")[:2] + lib["name"].split(":")[3:])
        if key in seen: continue
        seen.add(key); p = lib_path(lib)
        (cp if os.path.isfile(p) else missing).append(p)
    cp.append(os.path.join(MC, "versions", van["id"], van["id"] + ".jar"))
    if missing: print("missing libraries (skipped):", *missing, sep="\n  ")
    # A packaged (MSIX) parent process sees AppData\Roaming redirected to AppData\Local\Packages\...\LocalCache\Roaming: Knot then compares the
    # classpath strings with differently-spelled code sources, treats Mixin/Loader as game libraries and loads them twice ("cannot be cast to
    # PreLaunchEntrypoint"). Copying the jars to a plain folder outside AppData keeps both spellings identical.
    if a.libcopy:
        import shutil
        out = []
        for p in cp:
            rel = os.path.relpath(p, MC); dst = os.path.join(a.libcopy, rel)
            if not os.path.isfile(dst) or os.path.getsize(dst) != os.path.getsize(p):
                os.makedirs(os.path.dirname(dst), exist_ok=True); shutil.copy2(p, dst)
            out.append(dst)
        cp = out
    natives = tempfile.mkdtemp(prefix="sugarsv_natives_")
    sub = {"${natives_directory}": natives, "${launcher_name}": "sugarsv-test", "${launcher_version}": "1", "${classpath}": os.pathsep.join(cp),
           "${auth_player_name}": "SugarSVTest", "${version_name}": VER, "${game_directory}": a.gamedir, "${assets_root}": os.path.join(MC, "assets"),
           "${assets_index_name}": van["assetIndex"]["id"], "${auth_uuid}": "00000000-0000-0000-0000-000000000000", "${auth_access_token}": "0",
           "${clientid}": "0", "${auth_xuid}": "0", "${version_type}": "release"}
    def s(x):
        for k, v in sub.items(): x = x.replace(k, v)
        return x
    jvm = ["-Xmx6G"] + [s(x) for x in van["arguments"]["jvm"] if isinstance(x, str)] + [s(x) for x in fab["arguments"]["jvm"] if isinstance(x, str)]
    game = [s(x) for x in van["arguments"]["game"] if isinstance(x, str)] + ["--width", a.width, "--height", a.height]
    env = dict(os.environ)
    if a.script: env["FSR4_SCRIPT"] = a.script
    else: env.pop("FSR4_SCRIPT", None)
    os.makedirs(a.gamedir, exist_ok=True)
    cmd = [JAVA] + jvm + [fab["mainClass"]] + game
    out = open(a.log, "w", encoding="utf-8") if a.log else None
    r = subprocess.run(cmd, cwd=a.gamedir, env=env, stdout=out or None, stderr=subprocess.STDOUT if out else None)
    sys.exit(r.returncode)

if __name__ == "__main__":
    main()
