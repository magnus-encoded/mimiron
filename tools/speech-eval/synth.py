#!/usr/bin/env python3
"""Synthesise mode-switch clips with Piper: several voices, speeds and noise.

Usage: synth.py VOICES_DIR OUT_DIR [--quick]

Writes OUT_DIR/clips/*.wav (16 kHz mono s16, as tehuti's sidecar records) and
OUT_DIR/clips.jsonl, one row per clip: kind (switch|negative), vocabulary,
channel, text, voice, speed, noise. Synthetic speech only: no player audio.
"""
import json
import pathlib
import sys
import wave

import numpy as np
from faster_whisper.audio import decode_audio
from piper import PiperVoice, SynthesisConfig

HERE = pathlib.Path(__file__).resolve().parent
RATE = 16000
# Push-to-talk clips start a little before speech and end ~150 ms after it
# (sidecar DEFAULT_SPEECH_TAIL_MS).
LEAD_S, TAIL_S = 0.35, 0.15

# (file stem, speaker id or None, label). Non-English voices read the English
# text with their own phonemiser: a rough stand-in for accented English.
VOICES = [
    ("en_US-lessac-medium", None, "us-lessac"),
    ("en_US-ryan-medium", None, "us-ryan"),
    ("en_US-amy-medium", None, "us-amy"),
    ("en_GB-alan-medium", None, "gb-alan"),
    ("en_GB-northern_english_male-medium", None, "gb-north"),
    ("en_US-libritts_r-medium", 12, "us-libritts12"),
    ("en_US-libritts_r-medium", 230, "us-libritts230"),
    ("en_US-libritts_r-medium", 611, "us-libritts611"),
    ("sv_SE-nst-medium", None, "accent-sv"),
    ("de_DE-thorsten-medium", None, "accent-de"),
    ("nl_NL-mls-medium", None, "accent-nl"),
]
SPEEDS = [0.85, 1.1]  # Piper length_scale: below 1 is faster speech
NOISES = ["clean", "pink10", "babble10"]

BABBLE_TEXT = [
    "The birch canoe slid on the smooth planks.",
    "Glue the sheet to the dark blue background.",
    "It is easy to tell the depth of a well.",
    "These days a chicken leg is a rare dish.",
    "Rice is often served in round bowls.",
    "The juice of lemons makes fine punch.",
]


def load_voices(voices_dir):
    cache = {}
    for stem, _, _ in VOICES:
        if stem not in cache:
            cache[stem] = PiperVoice.load(str(pathlib.Path(voices_dir) / f"{stem}.onnx"))
    return cache


def speak(voice, text, speaker, speed, tmp):
    config = SynthesisConfig(speaker_id=speaker, length_scale=speed)
    chunks = list(voice.synthesize(text, syn_config=config))
    with wave.open(str(tmp), "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(chunks[0].sample_rate)
        for chunk in chunks:
            out.writeframes(chunk.audio_int16_bytes)
    return decode_audio(str(tmp), sampling_rate=RATE)


def pink(n, rng):
    white = rng.standard_normal(n)
    spectrum = np.fft.rfft(white)
    spectrum /= np.sqrt(np.arange(1, len(spectrum) + 1))
    noise = np.fft.irfft(spectrum, n)
    return noise / (np.std(noise) + 1e-9)


def mix(speech, noise, snr_db):
    power = np.mean(speech[np.abs(speech) > 0.01] ** 2) if np.any(np.abs(speech) > 0.01) else 1e-6
    scale = np.sqrt(power / (10 ** (snr_db / 10))) / (np.std(noise) + 1e-9)
    return speech + noise[: len(speech)] * scale


def write(path, audio):
    pcm = np.clip(audio, -1, 1)
    with wave.open(str(path), "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(RATE)
        out.writeframes((pcm * 32767).astype("<i2").tobytes())


def main():
    voices_dir, out = sys.argv[1], pathlib.Path(sys.argv[2])
    quick = "--quick" in sys.argv
    (out / "clips").mkdir(parents=True, exist_ok=True)
    tmp = out / "tmp.wav"
    voices = load_voices(voices_dir)
    rng = np.random.default_rng(526)

    babble = np.zeros(RATE * 12, dtype=np.float32)
    for i, sentence in enumerate(BABBLE_TEXT):
        stem, speaker, _ = VOICES[(i * 3) % len(VOICES[:8])]
        part = np.tile(speak(voices[stem], sentence, speaker, 1.0, tmp), 4)[: len(babble)]
        babble[: len(part)] += np.roll(part, i * 5000)[: len(part)]

    vocabularies = json.loads((HERE / "vocabularies.json").read_text())
    items = []
    for name, vocabulary in vocabularies.items():
        for channel, text in vocabulary["phrases"].items():
            items.append({"kind": "switch", "vocabulary": name, "channel": channel, "text": text})
    for line in (HERE / "negatives.txt").read_text().splitlines():
        if line.strip() and not line.startswith("#"):
            items.append({"kind": "negative", "vocabulary": None, "channel": None, "text": line.strip()})

    # One synthesis per (text, voice, speed); identical phrases share audio.
    voices_used = VOICES[:3] if quick else VOICES
    rows, seen = [], {}
    for item in items:
        for stem, speaker, label in voices_used:
            for speed in SPEEDS:
                key = (item["text"], label, speed)
                if key not in seen:
                    seen[key] = speak(voices[stem], item["text"], speaker, speed, tmp)
                speech = np.concatenate([np.zeros(int(LEAD_S * RATE)), seen[key],
                                         np.zeros(int(TAIL_S * RATE))]).astype(np.float32)
                # Negatives: clean and one noise level are enough to find false switches.
                for noise in (NOISES[:2] if item["kind"] == "negative" else NOISES):
                    if noise == "clean":
                        audio = speech
                    elif noise == "pink10":
                        audio = mix(speech, pink(len(speech), rng), 10)
                    else:
                        start = rng.integers(0, len(babble) - len(speech))
                        audio = mix(speech, babble[start:start + len(speech)], 10)
                    clip = f"{len(rows):05d}.wav"
                    write(out / "clips" / clip, audio)
                    rows.append({**item, "voice": label, "speed": speed, "noise": noise, "clip": clip})
    tmp.unlink(missing_ok=True)
    with open(out / "clips.jsonl", "w") as manifest:
        for row in rows:
            manifest.write(json.dumps(row) + "\n")
    print(f"{len(rows)} clips", file=sys.stderr)


if __name__ == "__main__":
    main()
