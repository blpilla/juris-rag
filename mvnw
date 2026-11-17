#!/usr/bin/env sh
set -eu

if ! command -v java >/dev/null 2>&1; then
  echo "JDK 21 ou superior é necessário para executar o Maven Wrapper." >&2
  exit 1
fi

MAVEN_VERSION=3.10.1
MAVEN_USER_HOME="${MAVEN_USER_HOME:-${HOME}/.m2}"
MAVEN_DIST_DIR="${MAVEN_USER_HOME}/wrapper/dists/apache-maven-${MAVEN_VERSION}"
MAVEN_HOME="${MAVEN_DIST_DIR}/apache-maven-${MAVEN_VERSION}"

if [ ! -x "${MAVEN_HOME}/bin/mvn" ]; then
  mkdir -p "${MAVEN_DIST_DIR}"
  ARCHIVE="${MAVEN_DIST_DIR}/apache-maven-${MAVEN_VERSION}-bin.zip"
  if command -v curl >/dev/null 2>&1; then
    curl --fail --location --silent --show-error \
      "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/${MAVEN_VERSION}/apache-maven-${MAVEN_VERSION}-bin.zip" \
      --output "${ARCHIVE}"
  elif command -v wget >/dev/null 2>&1; then
    wget -q "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/${MAVEN_VERSION}/apache-maven-${MAVEN_VERSION}-bin.zip" -O "${ARCHIVE}"
  else
    echo "Instale curl ou wget para baixar o Maven ${MAVEN_VERSION}." >&2
    exit 1
  fi
  if ! command -v unzip >/dev/null 2>&1; then
    echo "Instale unzip para preparar o Maven ${MAVEN_VERSION}." >&2
    exit 1
  fi
  unzip -q -o "${ARCHIVE}" -d "${MAVEN_DIST_DIR}"
fi

exec "${MAVEN_HOME}/bin/mvn" "$@"
