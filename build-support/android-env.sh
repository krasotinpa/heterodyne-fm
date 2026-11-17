# Source this file from Bash startup before the non-interactive early return.
export ANDROID_HOME="${ANDROID_HOME:-"$HOME/Android/Sdk"}"
for heterodyne_android_bin in "$ANDROID_HOME/platform-tools" "$ANDROID_HOME/cmdline-tools/latest/bin" "$ANDROID_HOME/emulator"; do
    case ":$PATH:" in
        *":$heterodyne_android_bin:"*) ;;
        *) PATH="$heterodyne_android_bin:$PATH" ;;
    esac
done
export PATH
unset heterodyne_android_bin
