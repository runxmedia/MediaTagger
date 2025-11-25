import os
import sys
import json
import torch
import whisper
import numpy as np
from pyannote.audio import Pipeline
import traceback
import logging

# By default, do not configure logging to avoid overly verbose output from libraries like Whisper and Pyannote.
# High-level status and errors are still printed to stdout/stderr.

def transcribe_video(path, hf_token):
    ffmpeg_paths = ["/opt/homebrew/bin", "/usr/local/bin"]
    os.environ["PATH"] = os.environ["PATH"] + os.pathsep + os.pathsep.join(ffmpeg_paths)

    # Prefer GPU acceleration when available
    if torch.cuda.is_available():
        device = "cuda"
    elif getattr(torch.backends, "mps", None) is not None and torch.backends.mps.is_available():
        device = "mps"
    else:
        print("No GPU or MPS device found. This script requires hardware acceleration.", file=sys.stderr)
        raise RuntimeError("GPU or MPS device required. CPU fallback is not supported.")

    try:
        audio_waveform = whisper.load_audio(path)
        if audio_waveform.size == 0:
            print("Warning: Audio waveform is empty.", file=sys.stderr)
    except Exception as e:
        print(f"Error loading audio: {e}", file=sys.stderr)
        traceback.print_exc(file=sys.stderr)
        raise

    print("PROGRESS:10", flush=True)

    model = whisper.load_model("large-v2", device="cpu")

    alignment_heads = model.alignment_heads
    model.alignment_heads = None
    model = model.to(device)
    model.register_buffer("alignment_heads", alignment_heads, persistent=False)

    _original_dtw = whisper.timing.dtw
    dtw_cpu = whisper.timing.dtw_cpu

    def patched_dtw(x: torch.Tensor, *args, **kwargs):
        if not torch.is_tensor(x) or x.device.type != "mps":
            return _original_dtw(x, *args, **kwargs)
        return dtw_cpu(x.cpu().double().numpy(), *args, **kwargs)

    whisper.timing.dtw = patched_dtw

    # Disable fp16 to prevent numerical instability on Apple Silicon devices.
    transcribe_options = {"word_timestamps": True, "fp16": False, "verbose": False}

    result = model.transcribe(audio_waveform, **transcribe_options)

    # Check if MPS failed to produce text (common issue on specific PyTorch versions on Mac)
    if not result.get("text", "").strip() and device == "mps":
        print("Warning: Whisper produced no text on MPS. This is a known PyTorch/MPS issue.", file=sys.stderr)
        print("Attempting fallback: Retrying transcription on CPU...", file=sys.stderr)

        # Move model to CPU and retry
        model = model.to("cpu")
        result = model.transcribe(audio_waveform, **transcribe_options)

        if result.get("text", "").strip():
            print("Success: CPU fallback produced text.", file=sys.stderr)

    if not result.get("text", "").strip():
        print("Warning: Whisper produced no text.", file=sys.stderr)
        print(f"Full transcription result: {result}", file=sys.stderr)

    print("PROGRESS:60", flush=True)

    diarize_model = Pipeline.from_pretrained(
        "pyannote/speaker-diarization-3.1",
        token=hf_token,
    )
    # We use the original 'device' variable here to ensure Diarization still uses GPU
    diarize_model.to(torch.device(device))

    audio_for_diarization = {
        "waveform": torch.from_numpy(audio_waveform).unsqueeze(0),
        "sample_rate": whisper.audio.SAMPLE_RATE,
    }

    diarization_out = diarize_model(audio_for_diarization)

    if hasattr(diarization_out, 'speaker_diarization') and diarization_out.speaker_diarization:
        annotation = diarization_out.speaker_diarization
    else:
        annotation = diarization_out

    segments = []
    if annotation:
        for turn, _, speaker in annotation.itertracks(yield_label=True):
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

    filtered = [s for s in result.get("segments", []) if s.get("text", "").strip()]
    result["segments"] = filtered
    print("PROGRESS:90", flush=True)
    return result

def main():
    if len(sys.argv) < 3:
        print("Usage: python detect_speech.py <video_file> <hf_token>", file=sys.stderr)
        sys.exit(1)

    video = sys.argv[1]
    token = sys.argv[2]

    print("PROGRESS:0", flush=True)
    try:
        result = transcribe_video(video, token)
        print("PROGRESS:100", flush=True)
        print("RESULTS:" + json.dumps(result), flush=True)
    except Exception:
        print("An error occurred during speech detection:", file=sys.stderr)
        traceback.print_exc(file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    main()