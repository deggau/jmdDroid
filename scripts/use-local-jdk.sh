# Source this file from the repository root to use the project-local JDK and Gradle.
JMDROID_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="$JMDROID_ROOT/.tools/jdk"
export GRADLE_USER_HOME="$JMDROID_ROOT/.tools/gradle-home"
export PATH="$JAVA_HOME/bin:$JMDROID_ROOT/.tools/gradle/bin:$PATH"

if [[ ! -x "$JAVA_HOME/bin/java" || ! -x "$JMDROID_ROOT/.tools/gradle/bin/gradle" ]]; then
  echo "JDK or Gradle is missing from .tools/. Re-run the local setup or use GitHub Actions." >&2
  return 1 2>/dev/null || exit 1
fi

echo "JDK: $(java -version 2>&1 | head -n 1)"
echo "Gradle: $(gradle --version | awk '/^Gradle / { print; exit }')"
