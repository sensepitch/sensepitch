#!/usr/bin/env bash
# Runs the full gate repeatedly to find flaky tests, several runs at the same time so the CPU is
# overloaded and timing dependent tests fail more often than in a single run.
#
# ROUNDS rounds of PARALLEL concurrent `./mvnw clean verify` runs. Each run of a round gets its own
# copy of the working tree (tracked and untracked, not ignored files, uncommitted changes
# included) in a clone of the repository, so their `target/` directories do not collide; the
# copies are reused by the following rounds. Before each start a random delay of up to
# MAX_DELAY_SECONDS staggers the runs, so they are in different phases (compile, unit tests,
# integration tests) and do not all start their test JVMs at once.
#
# A failed test does not stop a run and a failed run does not stop the others. Each run writes to
# RUNS_DIR/run-N (numbered 1 to ROUNDS * PARALLEL): `maven.log`, `exit-code` and the Surefire and
# Failsafe reports, the input of `flaky_report.py`.
#
# Usage: flaky_runs.sh RUNS_DIR [MAVEN_ARG...]
#   extra arguments are passed to every Maven run, e.g. `-Dfast` for a quick local check
# Environment: PARALLEL (default 5), ROUNDS (default 5), MAX_DELAY_SECONDS (default 5),
#   WORK_DIR for the copies of the working tree (default a new temporary directory, removed at the
#   end)
set -euo pipefail

if [ $# -lt 1 ]; then
  sed -n '2,/^set /p' "$0" | sed '$d; s/^# \{0,1\}//' >&2
  exit 2
fi
runs_dir=$(realpath -m "$1")
shift
parallel=${PARALLEL:-5}
rounds=${ROUNDS:-5}
max_delay=${MAX_DELAY_SECONDS:-5}
source_dir=$(git rev-parse --show-toplevel)
if [ -n "${WORK_DIR:-}" ]; then
  work_dir=$WORK_DIR
  mkdir -p "$work_dir"
else
  work_dir=$(mktemp -d)
  trap 'rm -rf "$work_dir"' EXIT
fi
# all runs share the local Maven repository; the resolver's file locks make concurrent downloads
# safe (Maven 3.9)
maven_args=(-B clean verify -Dmaven.test.failure.ignore=true
  -Daether.syncContext.named.factory=file-lock -Daether.syncContext.named.nameMapper=file-gav "$@")

# the wrapper downloads Maven on first use, once here and not five times at the same time
(cd "$source_dir" && ./mvnw -B -q -v > /dev/null)

mkdir -p "$runs_dir"
for slot in $(seq 1 "$parallel"); do
  copy="$work_dir/slot-$slot"
  # a clone for the Git metadata the build reads (commit id), the working tree copied over it
  rm -rf "$copy"
  git clone -q --shared --no-checkout "$source_dir" "$copy"
  (cd "$source_dir" && git ls-files -z --cached --others --exclude-standard \
    | xargs -0 -r sh -c 'for f; do [ -e "$f" ] && printf "%s\0" "$f"; done' sh \
    | tar --null -T - -cf -) | tar -xf - -C "$copy"
done

# One verify run in the copy of its slot, output only to its log.
run() {
  local number=$1 copy=$2
  local dir="$runs_dir/run-$number"
  mkdir -p "$dir"
  local code=0
  (cd "$copy" && ./mvnw "${maven_args[@]}") > "$dir/maven.log" 2>&1 || code=$?
  echo "$code" > "$dir/exit-code"
  for reports in target/surefire-reports target/failsafe-reports; do
    if [ -d "$copy/$reports" ]; then
      cp -r "$copy/$reports" "$dir/"
    fi
  done
  echo "run $number: maven exit $code"
}

pids=()
trap 'kill "${pids[@]}" 2> /dev/null' INT TERM
for round in $(seq 1 "$rounds"); do
  echo "round $round of $rounds, $parallel runs"
  pids=()
  for slot in $(seq 1 "$parallel"); do
    sleep "$(awk -v max="$max_delay" -v seed="$RANDOM" 'BEGIN { srand(seed); print rand() * max }')"
    number=$(((round - 1) * parallel + slot))
    echo "run $number: start"
    run "$number" "$work_dir/slot-$slot" &
    pids+=($!)
  done
  wait "${pids[@]}"
done
