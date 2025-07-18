import os
import sys
import json
import torch
import whisper
from pyannote.audio import Pipeline
import traceback

# --- MODIFIED: This function now accepts the hf_token as an argument ---
def transcribe_video(path, hf_token):
    # Prefer GPU acceleration when available. On Apple Silicon this means MPS.
    if torch.cuda.is_available():
        device = "cuda"
    elif getattr(torch.backends, "mps", None) is not None and torch.backends.mps.is_available():
        device = "mps"
    else:
        raise RuntimeError("GPU or MPS device required. CPU fallback is not supported.")

    audio = whisper.load_audio(path)
    print("PROGRESS:10", flush=True)

    # Load the Whisper model on CPU
    model = whisper.load_model("large-v2", device="cpu")

    # --- START OF FIX ---
    # The model includes a sparse alignment_heads buffer, which is incompatible with MPS.
    # We remove it from the model before moving to the device, and then restore it.

    # 1. Save the sparse alignment_heads buffer and remove it from the model.
    alignment_heads = model.alignment_heads
    model.alignment_heads = None

    # 2. Move the rest of the model to the MPS device.
    model = model.to(device)

    # 3. Restore the sparse alignment_heads buffer on the CPU.
    model.register_buffer("alignment_heads", alignment_heads, persistent=False)
    # --- END OF FIX ---

    result = model.transcribe(audio, word_timestamps=True)
    print("PROGRESS:60", flush=True)

    diarize_model = Pipeline.from_pretrained(
        "pyannote/speaker-diarization-3.1", use_auth_token=hf_token
    )
    # Move diarization model to the same device
    diarize_model.to(torch.device(device))
    diarization = diarize_model(path)

    # --- Assign speaker labels to words and segments ---
    segments = []
    for turn, _, speaker in diarization.itertracks(yield_label=True):
        segments.append({"start": turn.start, "end": turn.end, "speaker": speaker})

    for seg in result.get("segments", []):
        word_speakers = []
        for word in seg.get("words", []):
            mid = (word["start"] + word["end"]) / 2
            spk = next(
                (d["speaker"] for d in segments if d["start"] <= mid <= d["end"]),
                None,
            )
            if spk is not None:
                word["speaker"] = spk
                word_speakers.append(spk)
        if word_speakers:
            seg["speaker"] = max(set(word_speakers), key=word_speakers.count)
    print("PROGRESS:90", flush=True)
    return result

# --- MODIFIED: The main function now parses the token from command-line arguments ---
def main():
    if len(sys.argv) < 3:
        print("Usage: python detect_speech.py <video_file> <hf_token>", file=sys.stderr)
        sys.exit(1)

    video = sys.argv[1]
    token = sys.argv[2] # The token is the second argument

    print("PROGRESS:0", flush=True)
    try:
        result = transcribe_video(video, token)

        # The transcript will no longer be written to a file automatically.
        # Instead, the calling application will display the results for user review
        # and save the file after any corrections are made.

        print("PROGRESS:100", flush=True)
        print("RESULTS:" + json.dumps(result), flush=True)
    except Exception:
        log_dir = os.path.expanduser("~/Desktop")
        os.makedirs(log_dir, exist_ok=True)
        log_file = os.path.join(log_dir, "detect_speech.log")
        with open(log_file, "w") as f:
            traceback.print_exc(file=f)
        print(f"❌ Speech detection failed. See {log_file} for details.", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()