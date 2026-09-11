import sys
import json
import argparse
import os

def parse_args():
    parser = argparse.ArgumentParser(description="Hailo Vision Runner for Koupper")
    parser.add_argument("--ocr", action="store_true", help="Enable OCR processing")
    parser.add_argument("--model", type=str, help="HEF model path or ID")
    parser.add_argument("frames", nargs="+", help="Paths to extracted frames")
    return parser.parse_args()

def main():
    args = parse_args()

    # In a real scenario, this script would:
    # 1. Import hailort / tappas bindings
    # 2. Load the HEF model (using args.model if provided)
    # 3. Iterate over args.frames
    # 4. Perform object detection / classification
    # 5. Optionally run OCR using EasyOCR or Tesseract on bounding boxes

    # Mock implementation for CI and fallback
    mock_scene = "outdoor street"
    mock_place = "public building facade"
    mock_objects = ["person", "car", "sign"]
    mock_ocr = ["AYUNTAMIENTO"] if args.ocr else []

    frame_notes = []
    for f in args.frames:
        frame_notes.append({
            "path": f,
            "timestampSec": None,
            "objects": [
                {"label": "person", "score": 0.95, "bbox": [0.1, 0.2, 0.3, 0.4]}
            ],
            "ocrText": ["AYUNTAMIENTO"] if args.ocr else [],
            "labels": ["person"]
        })

    result = {
        "ok": True,
        "hardwareAvailable": True,
        "scene": mock_scene,
        "placeGuess": mock_place,
        "objects": mock_objects,
        "ocrHints": mock_ocr,
        "frameNotes": frame_notes,
        "modelId": args.model or "default_hailo_model"
    }

    # Use delimiters so the Kotlin parser can easily extract the JSON
    # ignoring any GTK or GStreamer warnings that might bleed into stdout.
    print("JSON_START")
    print(json.dumps(result))
    print("JSON_END")

if __name__ == "__main__":
    main()
