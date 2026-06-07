#!/usr/bin/env sh
set -eu

usage() {
  cat <<'EOF'
Install the Aggo CLI launcher.

Usage:
  install-aggo-cli.sh --main-class com.example.db.MigrationsKt [options]

Options:
  --main-class CLASS      Required Kotlin/Java main class that calls AggoMigrateTask.runFromArgs(args).
  --project-dir DIR      Project directory where aggo should run. Defaults to current directory.
  --install-dir DIR      Directory where the executable is written. Defaults to ~/.local/bin.
  --command NAME         Executable name. Defaults to aggo.
  --runner auto|maven|gradle
                          Build runner used by the executable. Defaults to auto.
  --gradle-task TASK     Gradle JavaExec runner task. Defaults to :aggoCliRun.
  --help                 Show this help.

Examples:
  ./scripts/install-aggo-cli.sh --main-class com.example.db.MigrationsKt
  ./scripts/install-aggo-cli.sh --main-class com.example.db.MigrationsKt --runner maven
  ./scripts/install-aggo-cli.sh --main-class com.example.db.MigrationsKt --install-dir /usr/local/bin
EOF
}

main_class=""
project_dir="$(pwd)"
install_dir="${HOME}/.local/bin"
command_name="aggo"
runner="auto"
gradle_task=":aggoCliRun"

has_gradle_build() {
  [ -x "$1/gradlew" ] ||
    [ -f "$1/build.gradle" ] ||
    [ -f "$1/build.gradle.kts" ] ||
    [ -f "$1/settings.gradle" ] ||
    [ -f "$1/settings.gradle.kts" ]
}

has_build_file() {
  has_gradle_build "$1" || [ -f "$1/pom.xml" ]
}

find_project_root() {
  current="$(cd "$1" && pwd)"
  while :; do
    if has_build_file "$current"; then
      printf '%s\n' "$current"
      return
    fi
    parent="$(dirname "$current")"
    if [ "$parent" = "$current" ]; then
      printf '%s\n' "$(cd "$1" && pwd)"
      return
    fi
    current="$parent"
  done
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --main-class)
      [ "$#" -ge 2 ] || { echo "error: --main-class requires a value" >&2; exit 2; }
      main_class="$2"
      shift 2
      ;;
    --main-class=*)
      main_class="${1#--main-class=}"
      shift
      ;;
    --project-dir)
      [ "$#" -ge 2 ] || { echo "error: --project-dir requires a value" >&2; exit 2; }
      project_dir="$2"
      shift 2
      ;;
    --project-dir=*)
      project_dir="${1#--project-dir=}"
      shift
      ;;
    --install-dir)
      [ "$#" -ge 2 ] || { echo "error: --install-dir requires a value" >&2; exit 2; }
      install_dir="$2"
      shift 2
      ;;
    --install-dir=*)
      install_dir="${1#--install-dir=}"
      shift
      ;;
    --command)
      [ "$#" -ge 2 ] || { echo "error: --command requires a value" >&2; exit 2; }
      command_name="$2"
      shift 2
      ;;
    --command=*)
      command_name="${1#--command=}"
      shift
      ;;
    --runner)
      [ "$#" -ge 2 ] || { echo "error: --runner requires a value" >&2; exit 2; }
      runner="$2"
      shift 2
      ;;
    --runner=*)
      runner="${1#--runner=}"
      shift
      ;;
    --gradle-task)
      [ "$#" -ge 2 ] || { echo "error: --gradle-task requires a value" >&2; exit 2; }
      gradle_task="$2"
      shift 2
      ;;
    --gradle-task=*)
      gradle_task="${1#--gradle-task=}"
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "error: unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

[ -n "$main_class" ] || { echo "error: --main-class is required" >&2; usage >&2; exit 2; }
project_dir="$(find_project_root "$project_dir")"

case "$command_name" in
  [A-Za-z][A-Za-z0-9_-]*) ;;
  *) echo "error: --command must match [A-Za-z][A-Za-z0-9_-]*" >&2; exit 2 ;;
esac

case "$runner" in
  auto|gradle|maven) ;;
  *) echo "error: --runner must be auto, gradle, or maven" >&2; exit 2 ;;
esac

mkdir -p "$install_dir"
target="${install_dir}/${command_name}"

cat > "$target" <<EOF
#!/usr/bin/env sh
set -eu

has_gradle_build() {
  [ -x "\$1/gradlew" ] ||
    [ -f "\$1/build.gradle" ] ||
    [ -f "\$1/build.gradle.kts" ] ||
    [ -f "\$1/settings.gradle" ] ||
    [ -f "\$1/settings.gradle.kts" ]
}

has_build_file() {
  has_gradle_build "\$1" || [ -f "\$1/pom.xml" ]
}

find_project_root() {
  current="\$(cd "\$1" && pwd)"
  while :; do
    if has_build_file "\$current"; then
      printf '%s\n' "\$current"
      return
    fi
    parent="\$(dirname "\$current")"
    if [ "\$parent" = "\$current" ]; then
      printf '%s\n' "\$(cd "\$1" && pwd)"
      return
    fi
    current="\$parent"
  done
}

run_gradle() {
  cd "\$project_dir"
  if [ -x ./gradlew ]; then
    exec ./gradlew -q '$gradle_task' --args="\$*"
  fi
  exec gradle -q '$gradle_task' --args="\$*"
}

run_maven() {
  cd "\$project_dir"
  exec mvn -q compile exec:java -Dexec.mainClass='$main_class' -Dexec.args="\$*"
}

start_dir="\${AGGO_PROJECT_DIR:-\$(pwd)}"
project_dir="\$(find_project_root "\$start_dir")"

case '$runner' in
  auto)
    if has_gradle_build "\$project_dir"; then
      run_gradle
    elif [ -f "\$project_dir/pom.xml" ]; then
      run_maven
    else
      echo "aggo: no Gradle or Maven build file found from \$start_dir" >&2
      exit 1
    fi
    ;;
  gradle)
    run_gradle
    ;;
  maven)
    run_maven
    ;;
  *)
    echo "aggo: invalid runner: $runner" >&2
    exit 1
    ;;
esac
EOF

chmod +x "$target"

echo "Installed Aggo CLI: $target"
echo "Run: $command_name migrate generate --name add_orders"
echo "If the command is not found, add $install_dir to PATH."
