PACKAGE  := it.lo.exp.saturn
ACTIVITY := .MainActivity
APK      := app/build/outputs/apk/debug/app-debug.apk

.PHONY: build install run release test clean distclean logcat bench bench-selftest

BENCH_VENV := bench/.venv
BENCH_PY   := $(BENCH_VENV)/bin/python

build:
	gradle --no-daemon assembleDebug

test:
	gradle --no-daemon testDebugUnitTest

install:
	gradle --no-daemon installDebug

run: install
	adb shell am start -n $(PACKAGE)/$(ACTIVITY)

release:
	gradle --no-daemon assembleRelease

clean:
	gradle --no-daemon clean

distclean: clean
	rm -rf .gradle/

logcat:
	adb logcat -s "Saturn"

# ---- model benchmark (standalone Python tool under bench/) ----

$(BENCH_PY):
	cd bench && python3 -m venv .venv && .venv/bin/pip install -q --upgrade pip && .venv/bin/pip install -q -r requirements.txt

bench: $(BENCH_PY)
	cd bench && .venv/bin/python -m saturn_bench.runner $(BENCH_ARGS)

bench-selftest: $(BENCH_PY)
	cd bench && .venv/bin/python -m saturn_bench.runner --selftest
