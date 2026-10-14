param(
    [string]$SdkRoot=$env:ANDROID_SDK_ROOT,
    [string]$JdkHome=$env:JAVA_HOME
)
$ErrorActionPreference='Stop'
if(!$SdkRoot -or !$JdkHome){throw '需要-SdkRoot和-JdkHome（或ANDROID_SDK_ROOT/JAVA_HOME）。'}
$themeBuild=Join-Path (Get-Location) ('work/watchworkshop-monet/helper-build/'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path "$themeBuild/classes","$themeBuild/dex","$PSScriptRoot/dist" -Force | Out-Null
$themeSources=@(Get-ChildItem -LiteralPath "$PSScriptRoot/native-src","$PSScriptRoot/vendor" -Recurse -Filter '*.java' -File|ForEach-Object{$_.FullName})
$themeAndroid=Join-Path $SdkRoot 'platforms/android-35/android.jar'
& "$JdkHome/bin/javac.exe" -encoding UTF-8 --release 17 -cp $themeAndroid -d "$themeBuild/classes" @themeSources
if($LASTEXITCODE -ne 0){throw 'javac failed'}
& "$JdkHome/bin/jar.exe" cf "$themeBuild/classes.jar" -C "$themeBuild/classes" .
if($LASTEXITCODE -ne 0){throw 'jar failed'}
& "$JdkHome/bin/java.exe" -cp "$SdkRoot/build-tools/35.0.0/lib/d8.jar" com.android.tools.r8.D8 --min-api 27 --lib $themeAndroid --output "$themeBuild/dex" "$themeBuild/classes.jar"
if($LASTEXITCODE -ne 0){throw 'd8 failed'}
& "$JdkHome/bin/jar.exe" cf "$PSScriptRoot/dist/WatchTheme-helper-v6.jar" -C "$themeBuild/dex" classes.dex
if($LASTEXITCODE -ne 0){throw 'dex jar failed'}
Get-FileHash -LiteralPath "$PSScriptRoot/dist/WatchTheme-helper-v6.jar" -Algorithm SHA256
