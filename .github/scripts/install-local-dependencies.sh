#!/usr/bin/env bash
set -euo pipefail
# Run from the repository root after downloading the pinned JARs.
# Hash-qualified versions prevent different private JARs sharing a Maven cache key.
sha256sum --check .github/dependencies.sha256

mvn -B --no-transfer-progress org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
    -Dfile="libs/ItemsAdder_3.5.0-r2.jar" -DgroupId="local" -DartifactId="ItemsAdder" \
    -Dversion="3.5.0-tfmc-0116d714822b" -Dpackaging=jar -DgeneratePom=true "$@"
mvn -B --no-transfer-progress org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
    -Dfile="libs/MMOItems-6.10.jar" -DgroupId="local" -DartifactId="MMOItems" \
    -Dversion="6.10-tfmc-c84700df5942" -Dpackaging=jar -DgeneratePom=true "$@"
mvn -B --no-transfer-progress org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
    -Dfile="libs/MythicLib-1.7.jar" -DgroupId="local" -DartifactId="MythicLib" \
    -Dversion="1.7-tfmc-660ff2a6ec86" -Dpackaging=jar -DgeneratePom=true "$@"
