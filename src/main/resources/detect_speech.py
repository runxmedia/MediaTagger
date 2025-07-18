import os
import sys
import json
import torch
import whisperx
try:
    # Newer versions of whisperx expose DiarizationPipeline under whisperx.diarize
    from whisperx.diarize import DiarizationPipeline
except Exception:  # Fallback for very old versions
    DiarizationPipeline = getattr(whisperx, "DiarizationPipeline", None)

# --- MODIFIED: This function now accepts the hf_token as an argument ---
def transcribe_video(path, hf_token):
    # Prefer GPU acceleration when available. On Apple Silicon this means MPS.
    if torch.cuda.is_available():
        device = "cuda"
        compute_type = "float16"
    elif getattr(torch.backends, "mps", None) is not None and torch.backends.mps.is_available():
        device = "mps"
        compute_type = "float16"
    else:
        device = "cpu"
        compute_type = "int8"

    audio = whisperx.load_audio(path)
    print("PROGRESS:10", flush=True)

    try:
        model = whisperx.load_model("large-v2", device, compute_type=compute_type)
        result = model.transcribe(audio)
    except ValueError as e:
        # CTranslate2 does not currently support Apple's MPS backend. Fall back
        # to the official Whisper implementation when running on MPS so we still
        # get GPU acceleration.
        if device == "mps" and "unsupported device mps" in str(e).lower():
            import whisper  # openai/whisper
            model = whisper.load_model("large-v2").to(device)
            result = model.transcribe(audio)
        else:
            raise
    print("PROGRESS:60", flush=True)

    model_a, metadata = whisperx.load_align_model(language_code=result["language"], device=device)
    result = whisperx.align(result["segments"], model_a, metadata, audio, device)

    if DiarizationPipeline is None:
        raise AttributeError("Installed whisperx does not provide DiarizationPipeline")

    # --- MODIFIED: Use the passed hf_token for authentication ---
    try:
        diarize_model = DiarizationPipeline(use_auth_token=hf_token, device=device)
    except Exception as e:
        raise RuntimeError(
            "Failed to initialize the diarization model. Ensure pyannote.audio is installed and a valid HF_TOKEN was provided."
        ) from e

    diarize_segments = diarize_model(audio)
    result = whisperx.assign_word_speakers(diarize_segments, result)
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
    result = transcribe_video(video, token)

    # The transcript will no longer be written to a file automatically.
    # Instead, the calling application will display the results for user review
    # and save the file after any corrections are made.

    print("PROGRESS:100", flush=True)
    print("RESULTS:" + json.dumps(result), flush=True)


if __name__ == "__main__":
    main()
