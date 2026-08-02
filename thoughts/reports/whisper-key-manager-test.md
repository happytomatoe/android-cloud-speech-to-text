# Whisper Key Manager Integration Test

*2026-08-02T07:59:58Z by Showboat 0.6.1*
<!-- showboat-id: de4e5d58-6370-43f7-96ad-ff3a69dfccfb -->

## Test Plan

1. Start Android emulator (Pixel 8, headful)
2. Install key-manager app
3. Configure Mistral credentials in key-manager
4. Test API key retrieval from key-manager
5. Install whisper-to-input
6. Test transcription using key from key-manager
7. Verify error handling

```bash
/var/home/l/Android/Sdk/emulator/emulator -avd Pixel_8 -gpu host -no-audio -no-snapshot-load &
```

```output
INFO         | Android emulator version 36.6.11.0 (build_id 15507667) (CL:N/A)
INFO         | Graphics backend: gfxstream
INFO         | Found systemPath /var/home/l/Android/Sdk/system-images/android-34/default/x86_64/
INFO         | Changing default hw.initialOrientation to portrait

INFO         | Guest GLES Driver: Auto (ext controls)
ERROR        | Your GPU cannot be used for hardware rendering. Consider using software rendering.
INFO         | emuglConfig_init: vulkan_mode_selected:host gles_mode_selected:host
INFO         | emuglConfig_get_vulkan_hardware_gpu_support_info: Found physical GPU 'Intel(R) UHD Graphics 620 (KBL GT2)', type: VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU, apiVersion: 1.4.354, driverVersion: 26.1.5

INFO         | emuglConfig_get_vulkan_hardware_gpu_support_info: Found physical GPU 'llvmpipe (LLVM 22.1.8, 256 bits)', type: VK_PHYSICAL_DEVICE_TYPE_CPU, apiVersion: 1.4.354, driverVersion: 26.1.5

INFO         | Enabled VulkanAllocateHostMemory feature for gpu vendor Intel(R) UHD Graphics 620 (KBL GT2)
INFO         | Checking system compatibility:
INFO         |   Checking: hasSufficientDiskSpace
INFO         |      Ok: Disk space requirements to run avd: `Pixel_8` are met
INFO         |   Checking: hasSufficientHwGpu
INFO         |      Ok: Hardware GPU requirements to run avd: `Pixel_8` are passed
INFO         |   Checking: hasSufficientSystem
INFO         |      Ok: System requirements to run avd: `Pixel_8` are met
WARNING      | File System is not ext4, disable QuickbootFileBacked feature
Authorization required, but no authorization protocol specified

INFO         | Warning: Could not find the Qt platform plugin "wayland" in "/var/home/l/Android/Sdk/emulator/lib64/qt/plugins" (:0, )
Authorization required, but no authorization protocol specified

INFO         | Warning: could not connect to display :0 (:0, )
INFO         | Warning: From 6.5.0, xcb-cursor0 or libxcb-cursor0 is needed to load the Qt xcb platform plugin. (:0, )
INFO         | Info: Could not load the Qt platform plugin "xcb" in "/var/home/l/Android/Sdk/emulator/lib64/qt/plugins" even though it was found. (:0, )
INFO         | Fatal: This application failed to start because no Qt platform plugin could be initialized. Reinstalling the application may fix this problem.

Available platform plugins are: offscreen, linuxfb, minimal, xcb, vnc.
 (:0, )
```

## Step 1: Start Emulator

Starting Pixel_8 headful emulator.

## Step 1: Start Emulator

Starting Pixel_8 headful emulator with swiftshader_indirect GPU.

```
$ adb devices -l
emulator-5554          device product:sdk_phone64_x86_64 model:Android_SDK_built_for_x86_64 device:emu64x transport_id:2
```

Emulator is online and ready.

```bash
/var/home/l/Android/Sdk/platform-tools/adb install -r /var/home/l/git/android-stt-key-manager/key-manager/app/build/outputs/apk/debug/app-debug.apk
```

```output
adb: failed to run abb_exec. Error: closed
adb: retrieving the default device installation mode failed: closed
adb: failed to run abb_exec. Error: device offline
Performing Streamed Install
adb: connect error for write: device offline
```
