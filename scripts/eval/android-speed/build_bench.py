"""Build an isolated no-network bench from exact production sources; never replace Wispr."""
from pathlib import Path
import argparse, hashlib, json, shutil, subprocess

REPO = Path(__file__).resolve().parents[3]
PACKAGE = 'com.envi.wispr.speedbench433'

def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def replace_once(text, old, new):
    if text.count(old) != 1:
        raise ValueError(f'transformation no longer has exactly one producer: {old!r}')
    return text.replace(old, new)

def build(root, logger):
    project = root / 'project'
    if project.exists():
        raise ValueError('a bench snapshot is immutable; use a new directory')
    src = project / 'app/src/main/java'
    src.mkdir(parents=True)
    (project/'settings.gradle.kts').write_text('pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\ndependencyResolutionManagement { repositories { google(); mavenCentral() } }\nrootProject.name="WisprSpeedBench433"\ninclude(":app")\n')
    (project/'build.gradle.kts').write_text('plugins { id("com.android.application") version "8.7.3" apply false; id("org.jetbrains.kotlin.android") version "2.0.21" apply false }\n')
    (project/'local.properties').write_text('sdk.dir=/Users/m4pro_sv/Android/sdk\n')
    (project/'gradle.properties').write_text('android.useAndroidX=true\norg.gradle.jvmargs=-Xmx3g\n')
    (project/'app/build.gradle.kts').write_text('''plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace="com.envi.wispr.speedbench433"; compileSdk=36
 defaultConfig { applicationId="com.envi.wispr.speedbench433"; minSdk=33; targetSdk=36; versionCode=1; versionName="433.1"; ndk { abiFilters += "arm64-v8a" } }
 compileOptions { sourceCompatibility=JavaVersion.VERSION_21; targetCompatibility=JavaVersion.VERSION_21 }
 kotlinOptions { jvmTarget="21" }
 packaging { jniLibs { useLegacyPackaging=true } }
}
dependencies { implementation("com.qualcomm.qti:geniex-android:0.4.0"); implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0"); implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0") }
''')
    (project/'app/src/main/AndroidManifest.xml').write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" xmlns:tools="http://schemas.android.com/tools"><uses-permission android:name="android.permission.INTERNET" tools:node="remove"/><uses-permission android:name="android.permission.RECORD_AUDIO" tools:node="remove"/><uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" tools:node="remove"/><application android:allowBackup="false" android:label="Wispr Speed Bench 433" android:theme="@android:style/Theme.Material.Light.NoActionBar"><activity android:name=".BenchActivity" android:exported="true" /></application></manifest>''')
    copied = []
    for pkg, names in {
        'asr': ['TdtModel.kt','TdtRecipe.kt','TdtRunner.kt'],
        'polish': ['S1Config.kt','S1ControlSettings.kt','S1PromptBuilder.kt','S1GenieXRuntime.kt','S1NativeLog.kt','S1BackendLoad.kt'],
    }.items():
        target = src/'com/envi/wispr'/pkg
        target.mkdir(parents=True)
        for name in names:
            original = REPO/'app/src/main/java/com/envi/wispr'/pkg/name
            shutil.copy2(original, target/name)
            copied.append({'path':str(original.relative_to(REPO)), 'sha256':digest(original)})
    model = (src/'com/envi/wispr/asr/TdtModel.kt').read_text()
    model = model.replace('TdtModel', 'TdtNoSpinModel')
    model = replace_once(model, 'setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)', 'setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)\n                addConfigEntry("session.intra_op.allow_spinning", "0")')
    (src/'com/envi/wispr/asr/TdtNoSpinModel.kt').write_text(model)
    recipe = (src/'com/envi/wispr/asr/TdtRecipe.kt').read_text()
    padding = 'val padded = if (n >= WINDOW_SAMPLES) samples else samples.copyOf(WINDOW_SAMPLES)'
    for name, minimum in [('TdtPad12Recipe','12 * 16000'),('TdtPad8Recipe','8 * 16000'),('TdtTail1Recipe','min(WINDOW_SAMPLES, n + 16000)')]:
        variant = recipe.replace('TdtRecipe',name)
        variant = replace_once(variant, padding, f'val padded = if (n >= WINDOW_SAMPLES) samples else samples.copyOf(max(n, {minimum}))')
        (src/f'com/envi/wispr/asr/{name}.kt').write_text(variant)
    runtime = (src/'com/envi/wispr/polish/S1GenieXRuntime.kt').read_text()
    # Keep shared GenerationEnd/exceptions in the untouched baseline file only.
    imports = runtime[:runtime.index('/** How one generation ended')]
    body = runtime[runtime.index('/** Single owner for S1 inference.') :]
    variant = (imports+body).replace('S1GenieXRuntime','S1BenchRuntime')
    variant = replace_once(variant, 'private val context: Context)', 'private val context: Context, private val benchBatch: Int, private val benchUBatch: Int)')
    variant = replace_once(variant, 'nBatch = S1Config.BATCH_SIZE', 'nBatch = benchBatch')
    variant = replace_once(variant, 'nUBatch = S1Config.UBATCH_SIZE', 'nUBatch = benchUBatch')
    (src/'com/envi/wispr/polish/S1BenchRuntime.kt').write_text(variant)
    budgets = REPO/'app/src/main/java/com/envi/wispr/polish/EngineDeadline.kt'
    budget_source = budgets.read_text()
    boundary = '/**\n * The winning expiry path'
    if budget_source.count(boundary) != 1:
        raise ValueError('budget class extraction boundary changed')
    (src/'com/envi/wispr/polish/BenchBudget.kt').write_text(budget_source[:budget_source.index(boundary)])
    copied.append({'path':str(budgets.relative_to(REPO)), 'sha256':digest(budgets), 'copied_part':'LocalPolishBudget, unchanged'})
    activity = Path(__file__).with_name('BenchActivity.kt')
    shutil.copy2(activity, src/'BenchActivity.kt')
    assets = project/'app/src/main/assets'
    assets.mkdir(parents=True)
    shutil.copy2(REPO/'app/src/main/assets/nemo128.onnx',assets/'nemo128.onnx')
    lib = project/'app/src/main/jniLibs/arm64-v8a'
    lib.mkdir(parents=True)
    shutil.copy2(logger,lib/'libenviouswispr-geniex-log.so')
    receipt = {
        'schema':1,'package':PACKAGE,
        'git':subprocess.check_output(['git','rev-parse','HEAD'],cwd=REPO,text=True).strip(),
        'originals':copied,'builder_sha256':digest(Path(__file__)),
        'native_logger_sha256':digest(logger),'asset_sha256':digest(assets/'nemo128.onnx'),
        'generated':{str(p.relative_to(project)):digest(p) for p in sorted(project.rglob('*')) if p.is_file()},
        'arms':{'A0':'unchanged CPU4 and15s recipe','A1':'same per-session runtime, intra-op spinning0','A2':'NOT RUN global pools excluded','A3':'12s minimum tensor padding, screening only','A4':'8s minimum tensor padding, screening only','A5':'real length+1s capped at15s, screening only','B0':'unchanged GPU Q4_K_M batch512','B1':'GPU Q4_K_M batch/ubatch256','B2':'GPU Q4_K_M batch/ubatch128'},
    }
    (root/'source-snapshot.json').write_text(json.dumps(receipt,indent=2)+'\n')
    return project

if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',required=True,type=Path)
    parser.add_argument('--logger',required=True,type=Path)
    args=parser.parse_args()
    print(build(args.root.resolve(),args.logger.resolve()))
