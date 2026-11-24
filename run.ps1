$ErrorActionPreference = 'Stop'
$javaHome = 'C:\Program Files\Java\jdk-25'
$javac = Join-Path $javaHome 'bin\javac.exe'
$java = Join-Path $javaHome 'bin\java.exe'
$classes = Join-Path $PSScriptRoot 'classes'
if (!(Test-Path $javac) -or !(Test-Path $java)) { throw "Java 25 was not found at $javaHome" }
New-Item -ItemType Directory -Force $classes | Out-Null
& $javac --release 25 -d $classes (Join-Path $PSScriptRoot 'src\MessengerServer.java')
if ($LASTEXITCODE -ne 0) { throw 'Java 25 compilation failed' }
& $java -cp $classes MessengerServer 8081
