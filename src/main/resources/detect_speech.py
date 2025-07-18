import os
import sys
import json
import torch
import whisper
from pyannote.audio import Pipeline
import traceback

def transcribe_video(path, hf_token):
    # Prefer GPU acceleration when available
    if torch.cuda.is_available():
        device = "cuda"
    elif getattr(torch.backends, "mps", None) is not None and torch.backends.mps.is_available():
        device = "mps"
    else:
        raise RuntimeError("GPU or MPS device required. CPU fallback is not supported.")

    # --- START OF FIX FOR LibsndfileError ---
    # 1. Load the audio waveform using whisper, which can handle video files.
    #    This returns a NumPy array.
    audio_waveform = whisper.load_audio(path)
    # --- END OF FIX ---

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

    # Transcribe using the loaded audio waveform
    result = model.transcribe(audio_waveform, word_timestamps=True)
    print("PROGRESS:60", flush=True)

    diarize_model = Pipeline.from_pretrained(
        "pyannote/speaker-diarization-3.1", use_auth_token=hf_token
    )
    diarize_model.to(torch.device(device))

    # --- START OF FIX FOR LibsndfileError ---
    # 2. Prepare the audio for pyannote. It expects a dictionary containing a
    #    2D torch.Tensor (channels, samples) and the sample rate.
    audio_for_diarization = {
        "waveform": torch.from_numpy(audio_waveform).unsqueeze(0),
        "sample_rate": whisper.audio.SAMPLE_RATE,
    }

    # 3. Pass the in-memory audio data to the diarization model.
    diarization = diarize_model(audio_for_diarization)
    # --- END OF FIX ---

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
        log_dir = os.path.expanduser("~/Desktop")
        os.makedirs(log_dir, exist_ok=True)
        log_file = os.path.join(log_dir, "detect_speech.log")
        with open(log_file, "w") as f:
            traceback.print_exc(file=f)
        print(f"❌ Speech detection failed. See {log_file} for details.", file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    main()