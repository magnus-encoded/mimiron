#!/usr/bin/env bash
# Full evaluation: synthesise, transcribe under each priming, score with Mimiron's matcher.
# Usage: run.sh VENV VOICES_DIR OUT_DIR [LIVE_VOCABULARY_FILE]
# Run it off the gaming machine (docs/notes/deploy.md): it keeps four cores busy for hours.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
venv=$1 voices=$2 out=$3 live=${4:-}
py="$venv/bin/python -I"
[ -f "$out/clips.jsonl" ] || $py "$here/synth.py" "$voices" "$out"
$py "$here/transcribe.py" "$out" none --kind switch --sample ${SAMPLE:-3}
$py "$here/transcribe.py" "$out" own --kind switch --sample ${SAMPLE:-3}
if [ -n "$live" ]; then
  $py "$here/transcribe.py" "$out" live --kind switch --vocabulary current --sample ${SAMPLE:-3} --live "$live"
fi
$py "$here/transcribe.py" "$out" none --kind negative --sample ${SAMPLE:-3}
for v in ${NEGATIVE_PRIMINGS:-current}; do
  $py "$here/transcribe.py" "$out" "fixed:$v" --kind negative --sample ${SAMPLE:-3}
done
(cd "$here/../.." && clojure -M -i tools/speech-eval/score.clj -m speech-eval.score "$out/transcripts.jsonl")
