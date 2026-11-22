#!/usr/bin/env bash
# Widget health: placed instances, last renders / errors, next alarm, jobs, process memory.
ADB=${ADB:-adb}
echo "== widgets";  "$ADB" shell dumpsys appwidget | grep -B2 "dev.livingsky.LivingSkyWidget" | grep -E "id=|host=" | head -4
echo "== renders (last 8)"; "$ADB" shell "logcat -d -s LivingSky AndroidRuntime:E" | grep -E "rendered|failed|Exception|FATAL" | tail -8
echo "== alarm";    "$ADB" shell dumpsys alarm | grep -A3 "dev.livingsky" | grep -E "when|origWhen|type" | head -3
echo "== jobs";     "$ADB" shell dumpsys jobscheduler | grep -E "JOB #.*dev.livingsky" | head -4
echo "== process";  "$ADB" shell dumpsys meminfo dev.livingsky | grep -E "TOTAL PSS|TOTAL:" | head -1
