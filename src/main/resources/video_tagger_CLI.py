import os
import numpy as np
import insightface
from insightface.app import FaceAnalysis
import argparse
import subprocess
import json
import faiss
import cv2
import sys

# --- Configuration ---
MODEL_NAME = "buffalo_l"
RECOGNITION_THRESHOLD = 1

def load_faiss_index(index_path, names_path):
    """Loads the FAISS index and names list from disk."""
    try:
        print(f"Loading FAISS index from '{index_path}'...")
        index = faiss.read_index(index_path)
        print(f"Loading names list from '{names_path}'...")
        with open(names_path, 'r') as f:
            names = json.load(f)
        print(f"Index loaded successfully with {index.ntotal} known faces.")
        return index, names
    except Exception as e:
        print(f"Error loading index files: {e}", file=sys.stderr)
        exit(1)

def get_video_info(video_path, ffprobe_path):
    """Gets video info using ffprobe, using the provided absolute path."""
    # MODIFIED: Use the full path to ffprobe passed as an argument
    cmd = [ffprobe_path, '-v', 'error', '-select_streams', 'v:0', '-show_entries', 'stream=width,height,nb_frames', '-of', 'json', video_path]
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, check=True)
        info = json.loads(result.stdout)['streams'][0]
        if 'nb_frames' not in info:
            # MODIFIED: Use the full path to ffprobe in the fallback command as well
            cmd_fallback = [ffprobe_path, '-v', 'error', '-count_frames', '-select_streams', 'v:0', '-show_entries', 'stream=nb_read_frames', '-of', 'default=nokey=1:noprint_wrappers=1', video_path]
            result_fallback = subprocess.run(cmd_fallback, capture_output=True, text=True, check=True)
            info['nb_frames'] = int(result_fallback.stdout.strip())
        width, height, total_frames = int(info['width']), int(info['height']), int(info['nb_frames'])
        return width, height, total_frames
    except Exception as e:
        # This error message is what you saw in the dialog
        print(f"Error getting video info with ffprobe: {e}", file=sys.stderr)
        return 0, 0, 0

# MODIFIED: Add ffmpeg_path and ffprobe_path to the function signature
def process_video_from_index(app, faiss_index, names, ffmpeg_path, ffprobe_path, args):
    show_preview = args.preview
    # MODIFIED: Pass ffprobe_path to the get_video_info function
    original_width, original_height, total_frames = get_video_info(args.video_path, ffprobe_path)

    if total_frames == 0:
        print("Could not determine total frames. Progress bar will be disabled.", file=sys.stderr)
        # Add an early exit to prevent the division by zero error
        print("Exiting due to failure to get video metadata.", file=sys.stderr)
        exit(1)

    # MODIFIED: Use the full path to ffmpeg passed as an argument
    ffmpeg_cmd = [ffmpeg_path, '-hwaccel', 'videotoolbox', '-i', args.video_path]
    if args.resize_width > 0:
        ffmpeg_cmd.extend(['-vf', f'scale={args.resize_width}:-1'])
        width, height = args.resize_width, int(original_height * (args.resize_width / original_width))
    else:
        width, height = original_width, original_height
    ffmpeg_cmd.extend(['-f', 'image2pipe', '-pix_fmt', 'bgr24', '-vcodec', 'rawvideo', '-'])
    process = subprocess.Popen(ffmpeg_cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    frame_size = width * height * 3
    found_faces_set = set()
    frame_count = 0
    should_quit = False
    print("PROGRESS:0", flush=True)

    while not should_quit:
        raw_frame = process.stdout.read(frame_size)
        if not raw_frame: break
        frame_count += 1

        if total_frames > 0 and frame_count % 15 == 0:
            progress_percent = int((frame_count / total_frames) * 100)
            print(f"PROGRESS:{progress_percent}", flush=True)

        if args.frame_skip > 1 and frame_count % args.frame_skip != 0: continue

        frame = np.frombuffer(raw_frame, dtype='uint8').reshape((height, width, 3))
        faces_in_frame = app.get(frame)

        preview_frame = frame.copy() if show_preview else None

        for face in faces_in_frame:
            normed_embedding = face.embedding / np.linalg.norm(face.embedding)
            query_embedding = np.array([normed_embedding]).astype('float32')
            distances, indices = faiss_index.search(query_embedding, 1)
            distance = distances[0][0]
            best_match_index = indices[0][0]
            best_match_name = "Unknown"

            if distance < RECOGNITION_THRESHOLD:
                best_match_name = names[best_match_index]
                found_faces_set.add(best_match_name)

            if show_preview:
                bbox = face.bbox.astype(int)
                color = (0, 255, 0) if best_match_name != "Unknown" else (0, 0, 255)
                cv2.rectangle(preview_frame, (bbox[0], bbox[1]), (bbox[2], bbox[3]), color, 2)
                cv2.putText(preview_frame, best_match_name, (bbox[0], bbox[1] - 10), cv2.FONT_HERSHEY_SIMPLEX, 0.6, color, 1)

        if show_preview:
            cv2.imshow('Media Tagger - Preview', preview_frame)
            if cv2.waitKey(1) & 0xFF == ord('q'):
                should_quit = True

    print("PROGRESS:100", flush=True)
    process.terminate()
    if show_preview:
        cv2.destroyAllWindows()

    sorted_names = sorted(list(found_faces_set))
    print(f"RESULTS:{json.dumps(sorted_names)}", flush=True)


def main():
    parser = argparse.ArgumentParser(description="High-speed video face recognition CLI.")
    parser.add_argument("video_path", help="Path to the video file to be processed.")
    parser.add_argument("index_path", help="Path to the FAISS index file.")
    parser.add_argument("names_path", help="Path to the names JSON file.")
    # MODIFIED: Add arguments to receive the absolute paths from Java
    parser.add_argument("--ffmpeg-path", required=True, help="Absolute path to the ffmpeg executable.")
    parser.add_argument("--ffprobe-path", required=True, help="Absolute path to the ffprobe executable.")
    parser.add_argument("--frame-skip", type=int, default=5, help="Process every N-th frame. Default: 5.")
    parser.add_argument("--resize-width", type=int, default=640, help="Resize frame width. Default: 640.")
    parser.add_argument("--preview", action="store_true", help="Show the live video processing window.")
    args = parser.parse_args()

    faiss_index, names_list = load_faiss_index(args.index_path, args.names_path)
    print("Initializing InsightFace...")
    app = FaceAnalysis(name=MODEL_NAME, providers=['CoreMLExecutionProvider', 'CPUExecutionProvider'])
    app.prepare(ctx_id=0, det_size=(640, 640))
    print("InsightFace initialized.")
    # MODIFIED: Pass the new path arguments to the processing function
    process_video_from_index(app, faiss_index, names_list, args.ffmpeg_path, args.ffprobe_path, args)

if __name__ == "__main__":
    main()