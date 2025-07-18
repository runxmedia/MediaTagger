#!/bin/bash
# This script installs dependencies, explicitly searching in common macOS paths.
# It creates a log file on the Desktop ONLY if the setup process fails.

# Define the final log file path for when an error occurs
LOG_DIR="$HOME/Desktop"
LOG_FILE="$LOG_DIR/install_dependencies.log"

# Create a secure temporary file to capture all output.
# Exit if the temporary file cannot be created.
TMP_LOG=$(mktemp) || { echo "FATAL: Could not create temp file."; exit 1; }

# --- Main Setup Function ---
# All installation logic is moved into this function.
# It returns 0 on success and 1 on any failure.
run_setup() {
  # --- MODIFIED: Validate Hugging Face Token from command-line argument ---
  HF_TOKEN=$1
  if [ -z "$HF_TOKEN" ]; then
      echo "Error: Hugging Face token was not provided as an argument to the installation script."
      return 1
  fi

  # Function to find a command in a specific list of directories.
  find_command() {
    local cmd_name=$1
    local search_paths=("/opt/homebrew/bin" "/usr/local/bin" "/usr/bin")
    for path in "${search_paths[@]}"; do
      if [ -x "$path/$cmd_name" ]; then
        echo "$path/$cmd_name"
        return 0
      fi
    done
    return 1
  }

  echo "Starting environment setup for Media Tagger..."
  echo "Log started at $(date)"
  echo "--------------------------------------------------"

  # --- Step 1: Find Homebrew ---
  echo "Searching for Homebrew in /opt/homebrew/bin and /usr/local/bin..."
  BREW_CMD=$(find_command "brew")
  if [ -z "$BREW_CMD" ]; then
      echo "Error: Homebrew 'brew' command not found in specified search paths."
      echo "Please install Homebrew by following the instructions at https://brew.sh/"
      return 1
  fi
  echo "Homebrew found at: $BREW_CMD"

  # --- Step 2: Check for and Install FFmpeg ---
  echo "Searching for FFmpeg..."
  if find_command "ffmpeg" &>/dev/null; then
    echo "FFmpeg executable found. Skipping installation."
  else
    echo "FFmpeg not found. Installing with Homebrew..."
    "$BREW_CMD" install ffmpeg
  fi

  # --- Step 3: Check for and Install Python ---
  echo "Searching for Python 3.11 or 3.10..."
  PYTHON_CMD=""
  # Prefer a compatible version of Python. WhisperX does not support 3.13+.
  for candidate in python3.11 python3.10 python3; do
    PYTHON_CMD=$(find_command "$candidate") && break
  done

  if [ -n "$PYTHON_CMD" ]; then
    # Verify the found Python version is < 3.13
    PY_VER=$("$PYTHON_CMD" -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")')
    MAJOR=${PY_VER%%.*}
    MINOR=${PY_VER#*.}
    if [ "$MAJOR" -ne 3 ] || [ "$MINOR" -ge 13 ]; then
      PYTHON_CMD=""
    fi
  fi

  if [ -z "$PYTHON_CMD" ]; then
    echo "Compatible Python not found. Installing Python 3.11 with Homebrew..."
    "$BREW_CMD" install python@3.11
    PYTHON_CMD=$(find_command "python3.11")
    if [ -z "$PYTHON_CMD" ]; then
        echo "Error: Could not find a compatible Python after installation."
        return 1
    fi
  else
    echo "Python found at: $PYTHON_CMD"
  fi

  # --- Step 4: Install Python Dependencies ---
  echo "Installing Python face recognition libraries using $PYTHON_CMD..."
  "$PYTHON_CMD" -m pip install --upgrade pip
  "$PYTHON_CMD" -m pip install --break-system-packages --user numpy torch torchaudio insightface faiss-cpu opencv-python tqdm onnxruntime
  "$PYTHON_CMD" -m pip install --break-system-packages --user openai-whisper
  # Install diarization dependencies
  "$PYTHON_CMD" -m pip install --break-system-packages --user pyannote.audio

  # --- Step 5: Download Diarization Model ---
  echo "Downloading pyannote/speaker-diarization-3.1 model..."
  "$PYTHON_CMD" - <<PY
from huggingface_hub import snapshot_download
import sys

# The token is passed in from the shell script variable
token = "${HF_TOKEN}"
try:
    snapshot_download("pyannote/speaker-diarization-3.1", use_auth_token=token, resume_download=True)
    print("Model download complete.")
except Exception as e:
    print(f"Failed to download model: {e}", file=sys.stderr)
    sys.exit(1)
PY

  # --- Step 6: Finalize ---
  # Check the exit code of the last command (pip install)
  if [ $? -eq 0 ]; then
      echo "--------------------------------------------------"
      echo "Environment setup completed successfully at $(date)."
      touch .dependencies_installed
      return 0
  else
      echo "--------------------------------------------------"
      echo "Error: Failed to install Python dependencies."
      echo "Please check the log for errors and try again."
      return 1
  fi
}

# --- Script Execution ---
echo "Starting environment setup... This may take a moment."

# Execute the setup function, redirecting all its output to the temporary log file.
# --- MODIFIED: Pass the shell script's first argument ($1) to the function ---
run_setup "$1" > "$TMP_LOG" 2>&1
SCRIPT_STATUS=$? # Capture the success (0) or failure (1) code from the function.

# --- Final User Feedback ---
# Check the status and decide whether to keep or discard the log.
if [ $SCRIPT_STATUS -eq 0 ]; then
    echo "✅ Setup completed successfully."
    # Cleanup the temporary log on success.
    rm "$TMP_LOG"
else
    # Ensure the Desktop directory exists before moving the log file
    mkdir -p "$LOG_DIR"
    mv "$TMP_LOG" "$LOG_FILE"
    echo "❌ Setup failed. A detailed log file has been saved to your Desktop:"
    echo "   $LOG_FILE"
fi

exit $SCRIPT_STATUS
