#!/bin/bash
PKG=com.darknesslord.cloudstream.dark
APK=$(ls app/build/outputs/apk/stable/release/*.apk | head -1)
adb install -r "$APK"
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 20
adb shell am force-stop $PKG
PREF=shared_prefs/${PKG}_preferences.xml
adb shell "run-as $PKG cat $PREF" > p.xml || true
run() {
  THEME=$1; APPT=$2; PCOL=$3; LAYOUT=$4
  sed -e '/dark_theme_/d' -e '/app_theme_key/d' -e '/primary_color_key/d' -e '/app_layout_key/d' p.xml | \
    sed "s#</map>#    <string name=\"dark_theme_preset\">$THEME</string>\n    <string name=\"app_theme_key\">$APPT</string>\n    <string name=\"primary_color_key\">$PCOL</string>\n    <int name=\"app_layout_key\" value=\"$LAYOUT\" />\n</map>#" > p2.xml
  adb push p2.xml /data/local/tmp/p2.xml >/dev/null
  adb shell "run-as $PKG sh -c 'cat /data/local/tmp/p2.xml > $PREF'"
  adb logcat -c
  adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null
  sleep 18
  echo "===== RUN theme=$THEME app_theme=$APPT color=$PCOL layout=$LAYOUT ====="
  adb logcat -d -s AndroidRuntime:E | grep -v "^\s*$" | grep -E "Caused by|Exception|Failed|TypedValue|at com\.google|at android\.content|at androidx" | tail -40
  adb shell dumpsys activity activities | grep -E "topResumedActivity" | head -1
  adb shell am force-stop $PKG
}
run OkyanusDerinligi AmoledLight Normal -1
exit 0
