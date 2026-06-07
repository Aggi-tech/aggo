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

case "$command_name" in
  [A-Za-z][A-Za-z0-9_-]*) ;;
  *) echo "error: --command must match [A-Za-z][A-Za-z0-9_-]*" >&2; exit 2 ;;
esac

case "$runner" in
  auto)
    if [ -x "$project_dir/gradlew" ]; then
      runner="gradle"
    else
      runner="maven"
    fi
    ;;
  gradle|maven) ;;
  *) echo "error: --runner must be auto, gradle, or maven" >&2; exit 2 ;;
esac

mkdir -p "$install_dir"
target="${install_dir}/${command_name}"

if [ "$runner" = "gradle" ]; then
  cat > "$target" <<EOF
#!/usr/bin/env sh
set -eu
cd '$project_dir'
exec ./gradlew -q '$gradle_task' --args="\$*"
EOF
else
  cat > "$target" <<EOF
#!/usr/bin/env sh
set -eu
cd '$project_dir'
exec mvn -q compile exec:java -Dexec.mainClass='$main_class' -Dexec.args="\$*"
EOF
fi

chmod +x "$target"

echo "Installed Aggo CLI: $target"
echo "Run: $command_name migrate generate --name add_orders"
echo "If the command is not found, add $install_dir to PATH."
