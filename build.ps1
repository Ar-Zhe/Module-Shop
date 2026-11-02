<#
.SYNOPSIS
    Build the 1.20.1 (Forge) version of Module-Shop.

.DESCRIPTION
    ForgeGradle needs a FULL JDK 17 (with javac) to compile Minecraft.
    Pointing at a JRE fails with "Java compiler is not available".
    If your default JDK is not 17, pass -JdkPath.

.PARAMETER JdkPath
    JDK 17 home directory, e.g. D:\Java\jdk-17. Omit to use the current JAVA_HOME.

.PARAMETER KubejsJar
    Path to the kubejs-forge jar. The KubeJS bridge is only compiled when this is given,
    so the main mod never depends on KubeJS.

.PARAMETER Task
    Gradle task, default "build". Also useful: runServer / runClient / clean.

.EXAMPLE
    .\build.ps1 -JdkPath 'C:\Java\jdk-17'
    .\build.ps1 -JdkPath 'C:\Java\jdk-17' -KubejsJar '.\libs\kubejs-forge-2001.6.5-build.26.jar'
    .\build.ps1 -JdkPath 'C:\Java\jdk-17' -Task runServer
#>
param(
    [string]$JdkPath = '',
    [string]$KubejsJar = '',
    [string]$Task = 'build'
)

# Gradle writes a lot to stderr (JAVA_TOOL_OPTIONS, deprecation warnings...),
# so ErrorActionPreference must not be Stop or a normal build looks like a failure.
$ErrorActionPreference = 'Continue'
$env:JAVA_TOOL_OPTIONS = '-Duser.language=en -Duser.country=US -Dfile.encoding=UTF-8'

$gradleArgs = @($Task, '--console=plain')
if ($JdkPath) { $gradleArgs += "-Dorg.gradle.java.home=$JdkPath" }
if ($KubejsJar) { $gradleArgs += "-PkubejsJar=$KubejsJar" }

& "$PSScriptRoot\gradlew.bat" @gradleArgs 2>&1 | ForEach-Object { Write-Host $_ }
exit $LASTEXITCODE