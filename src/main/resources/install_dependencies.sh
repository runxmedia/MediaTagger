#!/bin/bash
# This script requires Homebrew to be installed on the system.

echo "Starting environment setup for Media Tagger..."

# --- Step 1: Check for Homebrew ---
if ! command -v brew &> /dev/null
then
    echo "Error: Homebrew is not installed."
    echo "Please install Homebrew by following the instructions at https://brew.sh/"
    exit 1
fi

# --- Step 2: Install FFmpeg ---
echo "Checking for FFmpeg..."
if ! brew list ffmpeg &>/dev/null; then
  echo "FFmpeg not found. Installing with Homebrew..."
  brew install ffmpeg
else
  echo "FFmpeg is already installed."
fi

# --- Step 3: Install Python ---
echo "Checking for Python 3..."
if ! brew list python &>/dev/null; then
  echo "Python not found. Installing with Homebrew..."
  brew install python
else
  echo "Python is already installed."
fi

# --- Step 4: Install Python Dependencies ---
echo "Installing Python face recognition libraries..."
python3 -m pip install --upgrade pip

# --- THIS IS THE MODIFIED LINE ---
python3 -m pip install --break-system-packages --user numpy insightface faiss-cpu opencv-python tqdm onnxruntime

# --- Step 5: Finalize ---
if [ $? -eq 0 ]; then
    echo "Environment setup completed successfully."
    # Create a flag file to prevent this script from running again
    touch .dependencies_installed
else
    echo "Error: Failed to install Python dependencies."
    echo "Please check the console for errors and try again."
    exit 1
fi