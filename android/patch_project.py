"""Turn Bubblewrap's generated TWA project into Lembar's WebView shell.

Usage: python3 android/patch_project.py <generated-project-dir> <repo-dir>
"""
import json
import pathlib
import shutil
import sys

proj = pathlib.Path(sys.argv[1])
repo = pathlib.Path(sys.argv[2])

pkg = json.loads((proj / "twa-manifest.json").read_text())["packageId"]
java = proj / "app/src/main/java" / pkg.replace(".", "/") / "LauncherActivity.java"
if not java.exists():
    sys.exit(f"generated LauncherActivity not found: {java}")
shutil.copy(repo / "android/LauncherActivity.java", java)

res = proj / "app/src/main/res"
for qualifier in ("values", "values-night"):
    (res / qualifier).mkdir(parents=True, exist_ok=True)
    shutil.copy(repo / f"android/res/{qualifier}/lembar_theme.xml", res / qualifier / "lembar_theme.xml")

manifest = proj / "app/src/main/AndroidManifest.xml"
text = manifest.read_text()
anchor = '<activity android:name="LauncherActivity"'
if text.count(anchor) != 1:
    sys.exit("LauncherActivity element not found exactly once in AndroidManifest.xml")
text = text.replace(anchor, anchor + """
            android:theme="@style/LembarTheme"
            android:launchMode="singleTask"
            android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout|keyboard|keyboardHidden|navigation|density"
            android:windowSoftInputMode="adjustResize\"""")
manifest.write_text(text)

# Keep the JavaScript bridge methods through R8. The generated release build minifies
# without any ProGuard file, so register one.
rules = proj / "app/proguard-rules.pro"
with rules.open("a") as f:
    f.write("\n# Lembar WebView bridge\n-keepclassmembers class * {\n    @android.webkit.JavascriptInterface <methods>;\n}\n")
gradle = proj / "app/build.gradle"
g = gradle.read_text()
if "proguard-rules.pro" not in g:
    if g.count("minifyEnabled true") != 1:
        sys.exit("minifyEnabled true not found exactly once in app/build.gradle")
    g = g.replace("minifyEnabled true", "minifyEnabled true\n            proguardFiles 'proguard-rules.pro'")
    gradle.write_text(g)

print(f"patched {java.relative_to(proj)}, theme, manifest, proguard rules")
