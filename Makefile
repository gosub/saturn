PACKAGE  := it.lo.exp.saturn
ACTIVITY := .MainActivity
APK      := app/build/outputs/apk/debug/app-debug.apk

.PHONY: build install run release test clean distclean logcat

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
