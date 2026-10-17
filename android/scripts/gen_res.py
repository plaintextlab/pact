"""Generates the Android manifest and resources (launcher aliases, per-theme icons). Run from the repo root: python android/scripts/gen_res.py android"""
import os
import sys

root = sys.argv[1]
R = os.path.join(root, "app/src/main/res")


def w(path, text):
    full = os.path.join(root, path) if not path.startswith("res/") else os.path.join(R, path[4:])
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w", encoding="utf-8", newline="\n") as f:
        f.write(text.lstrip("\n"))


# Gradle: datastore dependency (Glance state uses Preferences).
p = os.path.join(root, "gradle/libs.versions.toml")
s = open(p, encoding="utf-8").read()
if "datastore" not in s:
    s = s.replace('work = "2.10.0"\n', 'work = "2.10.0"\ndatastore = "1.1.1"\n')
    s = s.replace("androidx-work-runtime =", 'androidx-datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }\nandroidx-work-runtime =')
    open(p, "w", encoding="utf-8", newline="\n").write(s)
p = os.path.join(root, "app/build.gradle.kts")
s = open(p, encoding="utf-8").read()
if "datastore" not in s:
    s = s.replace("    implementation(libs.androidx.work.runtime)\n", "    implementation(libs.androidx.work.runtime)\n    implementation(libs.androidx.datastore.preferences)\n")
    open(p, "w", encoding="utf-8", newline="\n").write(s)

LAUNCHER = [("Pact", "launcher_pact", True), ("ToDo", "launcher_todo", False), ("Tasks", "launcher_tasks", False),
            ("Todolist", "launcher_todolist", False), ("Checklist", "launcher_checklist", False), ("Reminders", "launcher_reminders", False)]
import json
THEMES = json.load(open(os.path.join(root, "..", "spec", "themes.json"), encoding="utf-8"))
# One launcher alias per (name, theme): Android can only change the icon/label by switching aliases.
aliases = "\n".join(f"""        <activity-alias android:name=".L_{n}_{th['id']}" android:targetActivity=".MainActivity" android:label="@string/{lbl}"
            android:icon="@mipmap/ic_launcher_{th['id']}" android:roundIcon="@mipmap/ic_launcher_{th['id']}"
            android:enabled="{str(n == 'Pact' and th['id'] == 'gruvbox').lower()}" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity-alias>""" for n, lbl, _ in LAUNCHER for th in THEMES)

w("app/src/main/AndroidManifest.xml", f"""
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" android:maxSdkVersion="32" />
    <uses-permission android:name="android.permission.USE_EXACT_ALARM" />
    <uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.VIBRATE" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.READ_CALENDAR" />

    <application
        android:name=".PactApp"
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.Pact">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTop"
            android:windowSoftInputMode="adjustResize" />

        <!-- Generated: one launcher entry per (name, theme); LauncherNames enables exactly one. -->
{aliases}

        <activity
            android:name=".notify.AlarmActivity"
            android:exported="false"
            android:excludeFromRecents="true"
            android:launchMode="singleInstance"
            android:showWhenLocked="true"
            android:turnScreenOn="true" />

        <activity
            android:name=".widget.WidgetConfigActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_CONFIGURE" />
            </intent-filter>
        </activity>

        <activity
            android:name=".widget.CalendarWidgetConfigActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_CONFIGURE" />
            </intent-filter>
        </activity>

        <receiver android:name=".notify.ReminderReceiver" android:exported="false" />
        <receiver android:name=".notify.ActionReceiver" android:exported="false" />
        <receiver android:name=".notify.BootReceiver" android:exported="false">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
                <action android:name="android.intent.action.TIME_SET" />
                <action android:name="android.intent.action.TIMEZONE_CHANGED" />
                <action android:name="android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" />
            </intent-filter>
        </receiver>

        <receiver android:name=".widget.PactWidgetReceiver" android:exported="true" android:label="@string/widget_name">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data android:name="android.appwidget.provider" android:resource="@xml/widget_info" />
        </receiver>

        <receiver android:name=".widget.CalendarWidgetReceiver" android:exported="true" android:label="@string/calendar_widget_name">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data android:name="android.appwidget.provider" android:resource="@xml/calendar_widget_info" />
        </receiver>

        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${{applicationId}}.files"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/file_paths" />
        </provider>
    </application>
</manifest>
""")

w("res/values/strings.xml", """
<resources>
    <string name="app_name">Pact</string>
    <string name="launcher_pact">Pact</string>
    <string name="launcher_todo">ToDo</string>
    <string name="launcher_tasks">Tasks</string>
    <string name="launcher_todolist">To-do list</string>
    <string name="launcher_checklist">Checklist</string>
    <string name="launcher_reminders">Reminders</string>
    <string name="widget_name">Pact tasks</string>
    <string name="widget_description">Your tasks for today, a group or the inbox</string>
    <string name="calendar_widget_name">Pact calendar</string>
    <string name="calendar_widget_description">Month view with your upcoming events</string>
</resources>
""")

for night, parent, bg in [("", "android:Theme.Material.Light.NoActionBar", "#FBF1C7"), ("-night", "android:Theme.Material.NoActionBar", "#282828")]:
    w(f"res/values{night}/themes.xml", f"""
<resources>
    <style name="Theme.Pact" parent="{parent}">
        <item name="android:windowBackground">@color/window_bg</item>
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
    </style>
</resources>
""")
    extra = "" if night else '\n    <color name="ic_bg">#282828</color>\n    <color name="ic_fg">#FABD2F</color>'
    w(f"res/values{night}/colors.xml", f"""
<resources>
    <color name="window_bg">{bg}</color>{extra}
</resources>
""")

w("res/xml/widget_info.xml", """
<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:configure="dev.pact.app.widget.WidgetConfigActivity"
    android:description="@string/widget_description"
    android:initialLayout="@layout/glance_default_loading_layout"
    android:minWidth="180dp"
    android:minHeight="110dp"
    android:minResizeWidth="110dp"
    android:minResizeHeight="80dp"
    android:targetCellWidth="3"
    android:targetCellHeight="3"
    android:resizeMode="horizontal|vertical"
    android:updatePeriodMillis="1800000"
    android:widgetCategory="home_screen"
    android:widgetFeatures="reconfigurable" />
""")

w("res/xml/calendar_widget_info.xml", """
<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:configure="dev.pact.app.widget.CalendarWidgetConfigActivity"
    android:description="@string/calendar_widget_description"
    android:initialLayout="@layout/glance_default_loading_layout"
    android:minWidth="250dp"
    android:minHeight="250dp"
    android:minResizeWidth="180dp"
    android:minResizeHeight="110dp"
    android:targetCellWidth="4"
    android:targetCellHeight="4"
    android:resizeMode="horizontal|vertical"
    android:updatePeriodMillis="1800000"
    android:widgetCategory="home_screen"
    android:widgetFeatures="reconfigurable" />
""")

w("res/xml/file_paths.xml", """
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <files-path name="data" path="data/" />
</paths>
""")

CHECK = "M54,30a24,24 0,1 0,0.01 0zM48,66l-11,-11 4.2,-4.2 6.8,6.8 15.8,-15.8 4.2,4.2z"
for name, fill in [("ic_launcher_foreground", "@color/ic_fg"), ("ic_launcher_monochrome", "#FFFFFFFF")]:
    w(f"res/drawable/{name}.xml", f"""
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="{fill}" android:fillType="evenOdd" android:pathData="{CHECK}" />
</vector>
""")

# Per-theme launcher icons, colored from each theme's dark palette.
NL = chr(10)
colors = NL.join(f'    <color name="ic_bg_{t["id"]}">{t["dark"]["bg"]}</color>{NL}    <color name="ic_fg_{t["id"]}">{t["dark"]["primary"]}</color>' for t in THEMES)
w("res/values/icon_colors.xml", f"""
<resources>
{colors}
</resources>
""")
for t in THEMES:
    i = t["id"]
    w(f"res/drawable/ic_launcher_foreground_{i}.xml", f"""
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="@color/ic_fg_{i}" android:fillType="evenOdd" android:pathData="{CHECK}" />
</vector>
""")
    w(f"res/mipmap-anydpi-v26/ic_launcher_{i}.xml", f"""
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_bg_{i}" />
    <foreground android:drawable="@drawable/ic_launcher_foreground_{i}" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
""")

w("res/drawable/ic_stat_pact.xml", """
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM10,17l-5,-5 1.41,-1.41L10,14.17l7.59,-7.59L19,8l-9,9z" />
</vector>
""")

w("res/drawable/ic_add.xml", """
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FF282828" android:pathData="M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" />
</vector>
""")

w("res/mipmap-anydpi-v26/ic_launcher.xml", """
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_bg" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
""")
print("ok")
