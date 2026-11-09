import os, sys, urllib.request, zipfile

SDK_DIR = r"D:\antigravity\light-vibes\tools\android-sdk"
os.makedirs(SDK_DIR, exist_ok=True)

# 1. Download Android-19 platform (android.jar)
PLATFORM_DIR = os.path.join(SDK_DIR, "platforms", "android-19")
if not os.path.isfile(os.path.join(PLATFORM_DIR, "android.jar")):
    print("Downloading Android API 19 platform (android.jar)...")
    url = "https://dl.google.com/android/repository/android-19_r04.zip"
    zip_path = os.path.join(SDK_DIR, "android-19.zip")
    urllib.request.urlretrieve(url, zip_path)
    print("Extracting android-19...")
    with zipfile.ZipFile(zip_path, 'r') as z:
        for member in z.infolist():
            # strip top-level folder 'android-4.4.2/'
            parts = member.filename.split('/', 1)
            if len(parts) > 1 and parts[1]:
                target = os.path.join(PLATFORM_DIR, parts[1])
                if member.is_dir():
                    os.makedirs(target, exist_ok=True)
                else:
                    os.makedirs(os.path.dirname(target), exist_ok=True)
                    with open(target, 'wb') as f:
                        f.write(z.read(member))
    os.remove(zip_path)
    print("android-19 platform installed successfully.")
else:
    print("android.jar already exists.")

# 2. Download Build-Tools 30.0.3
BT_DIR = os.path.join(SDK_DIR, "build-tools", "30.0.3")
if not os.path.isfile(os.path.join(BT_DIR, "aapt.exe")):
    print("Downloading Android Build-Tools 30.0.3 for Windows...")
    url = "https://dl.google.com/android/repository/build-tools_r30.0.3-windows.zip"
    zip_path = os.path.join(SDK_DIR, "build-tools.zip")
    urllib.request.urlretrieve(url, zip_path)
    print("Extracting build-tools...")
    with zipfile.ZipFile(zip_path, 'r') as z:
        for member in z.infolist():
            # strip top-level 'android-11/'
            parts = member.filename.split('/', 1)
            if len(parts) > 1 and parts[1]:
                target = os.path.join(BT_DIR, parts[1])
                if member.is_dir():
                    os.makedirs(target, exist_ok=True)
                else:
                    os.makedirs(os.path.dirname(target), exist_ok=True)
                    with open(target, 'wb') as f:
                        f.write(z.read(member))
    os.remove(zip_path)
    print("build-tools 30.0.3 installed successfully.")
else:
    print("build-tools already exists.")

print("SDK Setup Complete:")
print("  android.jar:", os.path.join(PLATFORM_DIR, "android.jar"))
print("  aapt.exe:   ", os.path.join(BT_DIR, "aapt.exe"))
print("  d8.bat:     ", os.path.join(BT_DIR, "d8.bat"))
print("  zipalign:   ", os.path.join(BT_DIR, "zipalign.exe"))
