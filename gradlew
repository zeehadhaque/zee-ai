#!/bin/sh
# Gradle startup script for POSIX / Linux / macOS
DIRNAME=$(dirname "$0")
if [ -x "$(command -v gradle)" ]; then
  exec gradle -p "$DIRNAME" "$@"
else
  echo "Gradle wrapper requires either local 'gradle' in PATH or Android Studio Gradle distribution."
  exit 1
fi
