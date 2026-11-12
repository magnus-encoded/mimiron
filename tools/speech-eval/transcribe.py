#!/usr/bin/env python3
"""Transcribe synthesised clips the way tehuti's speech sidecar does.

Usage: transcribe.py OUT_DIR PRIMING [--kind switch|negative] [--vocabulary NAME]
                     [--sample N] [--shard K/N] [--live FILE]

PRIMING is one of
  none           no hotwords
  live           the words in FILE (--live), in order: today's global vocabulary
  own            each switch clip primed with its own vocabulary's hotwords
  fixed:<name>   every clip primed with vocabulary <name>'s hotwords

Mirrors tehuti sidecars/speech/speech.py (v0.123.0): faster-whisper "small",
int8, beam 5, English, VAD with the sidecar's parameters and a retry without
VAD on empty text, the hallucination filter, and the 64-token hotword cap.
--sample N keeps a fixed 1/N of clips (by clip name hash), the same for every
PRIMING, so conditions compare on identical audio. --shard K/N takes every Nth
of those clips, so N processes (THREADS=4/N each) can share the cores. Appends rows to OUT_DIR/transcripts.jsonl and skips clips already done.
"""
import hashlib
import json
import os
import pathlib
import re
import sys

from faster_whisper import WhisperModel

HERE = pathlib.Path(__file__).resolve().parent
MAX_HOTWORD_TOKENS = 64

HALLUCINATION = re.compile(
    r"(thank you|thanks)( so much| very much)?( for watching| for listening)?"
    r"( and)?( (bye|goodbye|bye bye|see you))*|(bye|goodbye|bye bye)( bye)*"
    r"|you|please subscribe.*|(like and )?subscribe.*"
    r"|see you (next time|in the next video)|subtitles by .*"
    r"|(amara|transcription by) .*|the end"
)


def transcript(segments):
    text = " ".join(
        segment.text.strip() for segment in segments
        if not (segment.no_speech_prob > 0.6 and segment.avg_logprob < -1.0)
    ).strip()
    normalized = " ".join(re.sub(r"[.!?,'\"]", "", text.lower()).replace("-", " ").split())
    return "" if HALLUCINATION.fullmatch(normalized) else text


def hotword_string(model, vocabulary):
    """Speech.set_vocabulary: dedupe, keep words while the list fits 64 tokens."""
    words, seen = [], set()
    for word in vocabulary:
        word = " ".join(word.split())
        if not word or word.casefold() in seen:
            continue
        seen.add(word.casefold())
        candidate = ", ".join([*words, word])
        if len(model.hf_tokenizer.encode(" " + candidate, add_special_tokens=False).ids) <= MAX_HOTWORD_TOKENS:
            words.append(word)
    return ", ".join(words)


def transcribe(model, path, hotwords):
    priming = {"hotwords": hotwords} if hotwords else {}
    segments, _ = model.transcribe(
        str(path), beam_size=5, language="en", vad_filter=True, **priming,
        vad_parameters={"threshold": 0.3, "min_speech_duration_ms": 80, "speech_pad_ms": 300},
    )
    text = transcript(segments)
    if text:
        return text
    segments, _ = model.transcribe(str(path), beam_size=5, language="en", vad_filter=False, **priming)
    return transcript(segments)


def main():
    out, priming = pathlib.Path(sys.argv[1]), sys.argv[2]
    args = sys.argv[3:]
    kind = args[args.index("--kind") + 1] if "--kind" in args else None
    live = args[args.index("--live") + 1] if "--live" in args else None
    only = args[args.index("--vocabulary") + 1] if "--vocabulary" in args else None
    sample = int(args[args.index("--sample") + 1]) if "--sample" in args else 1
    shard, shards = map(int, (args[args.index("--shard") + 1] if "--shard" in args else "0/1").split("/"))
    vocabularies = json.loads((HERE / "vocabularies.json").read_text())
    model = WhisperModel(os.environ.get("TEHUTI_SPEECH_MODEL", "small"), device="cpu",
                         compute_type="int8", cpu_threads=int(os.environ.get("THREADS", "4")))

    if priming == "live":
        words = [line.strip() for line in open(live) if line.strip() and not line.startswith("#")]
        fixed = hotword_string(model, words)
    elif priming.startswith("fixed:"):
        fixed = hotword_string(model, vocabularies[priming[6:]]["hotwords"])
    else:
        fixed = ""
    own = {name: hotword_string(model, v["hotwords"]) for name, v in vocabularies.items()}

    results = out / "transcripts.jsonl"
    done = set()
    if results.exists():
        for line in results.read_text().splitlines():
            row = json.loads(line)
            done.add((row["clip"], row["priming"]))
    rows = [json.loads(line) for line in (out / "clips.jsonl").read_text().splitlines()]
    with open(results, "a") as output:
        for row in rows:
            if kind and row["kind"] != kind:
                continue
            if only and row["vocabulary"] != only:
                continue
            if int(hashlib.md5(row["clip"].encode()).hexdigest(), 16) % sample:
                continue
            if int(row["clip"][:5]) % shards != shard:
                continue
            if priming == "own" and row["kind"] != "switch":
                continue
            if (row["clip"], priming) in done:
                continue
            hotwords = own[row["vocabulary"]] if priming == "own" else fixed
            heard = transcribe(model, out / "clips" / row["clip"], hotwords)
            output.write(json.dumps({**row, "priming": priming, "heard": heard}) + "\n")
            output.flush()


if __name__ == "__main__":
    main()
