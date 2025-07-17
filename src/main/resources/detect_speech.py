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

# Attempt to read a Hugging Face token from the environment to allow
# downloading the diarization model when required.  Some pyannote models
# require authentication, and failing to provide a token may lead to the
# pipeline failing to load with an AttributeError.
HF_TOKEN = os.environ.get("HF_TOKEN")


def transcribe_video(path):
    device = "cuda" if torch.cuda.is_available() else "cpu"
    model = whisperx.load_model("large-v2", device, compute_type="float16" if device == "cuda" else "int8")
    audio = whisperx.load_audio(path)
    print("PROGRESS:10", flush=True)
    result = model.transcribe(audio)
    print("PROGRESS:60", flush=True)

    model_a, metadata = whisperx.load_align_model(language_code=result["language"], device=device)
    result = whisperx.align(result["segments"], model_a, metadata, audio, device)

    if DiarizationPipeline is None:
        raise AttributeError("Installed whisperx does not provide DiarizationPipeline")

    # Pass the HF token to the pipeline if available.  If HF_TOKEN is not set,
    # WhisperX will attempt an anonymous download which may fail for protected
    # models, so we warn the user to set the token for reliability.
    try:
        diarize_model = DiarizationPipeline(use_auth_token=HF_TOKEN or False, device=device)
    except Exception as e:
        raise RuntimeError(
            "Failed to initialize the diarization model. Ensure pyannote.audio is installed and HF_TOKEN is set if required."
        ) from e

    if HF_TOKEN is None:
        print("WARNING: HF_TOKEN not set. Diarization model download may fail.", file=sys.stderr)

    diarize_segments = diarize_model(audio)
    result = whisperx.assign_word_speakers(diarize_segments, result)
    print("PROGRESS:90", flush=True)
    return result


def main():
    if len(sys.argv) < 2:
        print("Usage: python detect_speech.py <video_file>", file=sys.stderr)
        sys.exit(1)

    video = sys.argv[1]
    print("PROGRESS:0", flush=True)
    result = transcribe_video(video)

    txt_file = os.path.splitext(video)[0] + ".txt"
    with open(txt_file, "w", encoding="utf-8") as f:
        for seg in result["segments"]:
            speaker = seg.get("speaker", "SPEAKER_0")
            start = seg.get("start", 0)
            end = seg.get("end", 0)
            text = seg.get("text", "").strip()
            f.write(f"[{start:.2f}-{end:.2f}] {speaker}: {text}\n")

    print("PROGRESS:100", flush=True)
    print("RESULTS:" + json.dumps(result), flush=True)


if __name__ == "__main__":
    main()
