# How to use A4 TV Bridge

## 1. Install the controller

Download `A4-TV-Bridge-1.0.1-debug.apk` from the [release page](https://github.com/RatherAsif04/A4-TV-Bridge-G2/releases/tag/v1.0.1). Open it on the Android phone or tablet you’ll use as the remote. If Android asks, allow that browser or file manager to install this APK. You can revoke that permission after installation. [Verify the APK](../SECURITY.md#verify-your-download)

## 2. Connect your TV

1. Put the controller and TV on the same trusted local network. Ethernet for the TV works if your Wi-Fi devices can reach it.
2. On the TV, open **Settings → About**. Select the Android TV OS build several times if you need to enable Developer Options. Open Developer Options and enable **Wireless debugging**, **Network debugging**, or **ADB debugging**, whichever your TV offers.
3. Open A4 TV Bridge and tap **Connect your TV**. Choose the method your TV supports.

| Method | What to enter |
| --- | --- |
| **Find TVs on this network** | Select your TV from the search results. Confirm the debugging prompt on the TV. |
| **Enter TV address** | Enter the TV’s local IP address and debugging port. Older TVs often use port `5555`. |
| **Pair with a code** | Copy the address and debugging port from the main Wireless debugging screen. Open **Pair device** on the TV and enter its separate pairing port and six-digit code. |

## 3. Control and capture the screen

Tap **Control** in the bottom dock. **Remote** offers D-pad and touchpad modes and buttons for navigation, playback, volume, and text entry. **Screen Mirror** shows a refreshed view of the TV that supports taps and swipes, with direction buttons below the image. Choose **Screenshot** or **Screen Record** to save a capture. Protected content may appear blank and recordings are silent.

## 4. Install apps and transfer files

Open **Apps & Files → Install Apps → Choose APKs → Browse device** to install an APK from your controller. For split APKs, select the base and all matching APKs together. **1 Click App Install** downloads a compatible stable APK from the selected app’s official GitHub release when a verified asset is available.

Use **File Manager** to open folders, upload from the controller, and manage files in TV shared storage. Use the file’s menu to save a copy to the controller. To send files to the TV’s Downloads, Movies, Music, Pictures, or Documents folder, open **Send files**, select a destination, and tap **Choose Files**. Files go into an `A4TVBridge` subfolder of that destination.

## 5. Monitor and maintain the TV

Open **Device Info** from the Dashboard or TV Care. Pick **Overview**, **CPU**, **Memory**, or **Network** to see available readings and recent activity. **TV Care → Clean Up TV** shows suggested app changes and their remarks. Use **Check my TV** to see which changes are active, and **Restore** to turn supported apps back on. Review the confirmation before clearing data or uninstalling an app.

## 6. Use the terminal

Tap **ADB Shell / Terminal** in the dock. Enter a non-interactive command such as `getprop ro.build.version.release`, then tap **Run command**. The console shows the output and exit code. Commands run with the connected TV’s ADB shell permissions.

## If the TV does not connect

- Confirm the TV and controller can reach each other. Guest Wi-Fi and access point isolation can block local connections.
- Check the port and keep the TV awake while approving its debugging prompt. A pairing port is separate from the connection port and can change when you reopen the pairing dialog.
- If automatic discovery misses a device, enter the address and port shown on the TV.
- Casting-only devices and TVs without an accessible ADB service cannot use these tools.
