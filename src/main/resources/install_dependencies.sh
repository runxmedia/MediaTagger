#!/bin/bash
# This script installs dependencies, explicitly searching in common macOS paths.
# It creates a log file on the Desktop ONLY if the setup process fails.

# Define the final log file path for when an error occurs
LOG_FILE="$HOME/Desktop/install_dependencies.log"

# Create a secure temporary file to capture all output.
# Exit if the temporary file cannot be created.
TMP_LOG=$(mktemp) || { echo "FATAL: Could not create temp file."; exit 1; }

# --- Main Setup Function ---
# All installation logic is moved into this function.
# It returns 0 on success and 1 on any failure.
run_setup() {
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
  echo "Searching for Python 3..."
  PYTHON_CMD=$(find_command "python3")
  if [ -n "$PYTHON_CMD" ]; then
    echo "Python 3 found at: $PYTHON_CMD"
  else
    echo "Python 3 not found. Installing with Homebrew..."
    "$BREW_CMD" install python
    PYTHON_CMD=$(find_command "python3")
    if [ -z "$PYTHON_CMD" ]; then
        echo "Error: Could not find 'python3' in search paths even after installation."
        return 1
    fi
  fi

  # --- Step 4: Install Python Dependencies ---
  echo "Installing Python face recognition libraries using $PYTHON_CMD..."
  "$PYTHON_CMD" -m pip install --upgrade pip
  "$PYTHON_CMD" -m pip install --break-system-packages --user numpy torch torchaudio whisperx insightface faiss-cpu opencv-python tqdm onnxruntime
  # WhisperX from GitHub with custom dependencies for newer Python versions
  "$PYTHON_CMD" -m pip install --break-system-packages --user --no-deps git+https://github.com/m-bain/whisperX.git
  "$PYTHON_CMD" -m pip install --break-system-packages --user ctranslate2==4.6.0 faster-whisper nltk

  # --- Step 5: Finalize ---
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
run_setup > "$TMP_LOG" 2>&1
SCRIPT_STATUS=$? # Capture the success (0) or failure (1) code from the function.

# --- Final User Feedback ---
# Check the status and decide whether to keep or discard the log.
if [ $SCRIPT_STATUS -eq 0 ]; then
    echo "✅ Setup completed successfully."
    # On success, remove the temporary log file.
    rm "$TMP_LOG"
else
    # On failure, move the temporary log to the user's Desktop.
    mv "$TMP_LOG" "$LOG_FILE"
    echo "❌ Setup failed. A detailed log file has been saved to your Desktop:"
    echo "   $LOG_FILE"
fi

exit $SCRIPT_STATUS
