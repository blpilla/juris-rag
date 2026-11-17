@echo off
setlocal
set MAVEN_VERSION=3.10.1
set MAVEN_USER_HOME=%USERPROFILE%\.m2
set MAVEN_HOME=%MAVEN_USER_HOME%\wrapper\dists\apache-maven-%MAVEN_VERSION%\apache-maven-%MAVEN_VERSION%
if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
  where java >nul 2>nul || (echo JDK 21 ou superior e necessario.& exit /b 1)
  where curl >nul 2>nul || (echo curl e necessario para baixar o Maven.& exit /b 1)
  if not exist "%MAVEN_USER_HOME%\wrapper\dists\apache-maven-%MAVEN_VERSION%" mkdir "%MAVEN_USER_HOME%\wrapper\dists\apache-maven-%MAVEN_VERSION%"
  curl --fail --location --silent --show-error "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/%MAVEN_VERSION%/apache-maven-%MAVEN_VERSION%-bin.zip" --output "%MAVEN_USER_HOME%\wrapper\dists\apache-maven-%MAVEN_VERSION%\maven.zip" || exit /b 1
  powershell -NoProfile -Command "Expand-Archive -Force '%MAVEN_USER_HOME%\wrapper\dists\apache-maven-%MAVEN_VERSION%\maven.zip' '%MAVEN_USER_HOME%\wrapper\dists\apache-maven-%MAVEN_VERSION%'" || exit /b 1
)
call "%MAVEN_HOME%\bin\mvn.cmd" %*
endlocal
