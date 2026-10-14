# Первичные технические источники

Проверены 14 октября 2026 года. Источники описывают API/tools, а не доказывают Super Radio/Toyota compatibility.
Master/latest меняются; закрепи dependency version и source SHA.

| Источник | Что подтверждает |
|---|---|
| [Android USB host](https://developer.android.com/develop/connectivity/usb/host) | Host hardware, permission, interfaces, I/O |
| [UsbDeviceConnection](https://developer.android.com/reference/android/hardware/usb/UsbDeviceConnection) | Descriptors, claim/force и transfers |
| [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) | Candidate library |
| [CDC driver source](https://raw.githubusercontent.com/mik3y/usb-serial-for-android/master/usbSerialForAndroid/src/main/java/com/hoho/android/usbserial/driver/CdcAcmSerialDriver.java) | Viewed open force-claims CDC; separate line coding/DTR/RTS requests |
| [CommonUsbSerialPort](https://raw.githubusercontent.com/mik3y/usb-serial-for-android/master/usbSerialForAndroid/src/main/java/com/hoho/android/usbserial/driver/CommonUsbSerialPort.java) | Lifecycle behavior, audit pinned version |
| [pySerial open](https://pyserial.readthedocs.io/en/latest/pyserial_api.html#serial.Serial.open) | RTS/DTR apply и possible driver glitch |
| [AOSP USB audio](https://source.android.com/docs/core/audio/usb) | Host/kernel/OEM policy requirements |
| [AudioRouting](https://developer.android.com/reference/android/media/AudioRouting) | Preferred versus actual active route |
| [Audio focus](https://developer.android.com/media/optimize/audio-focus) | Focus lifecycle и target/API requirements |
| [Microsoft USBView](https://learn.microsoft.com/en-us/windows-hardware/drivers/debugger/usbview) | Descriptor tree |
| [USB descriptors](https://learn.microsoft.com/en-us/windows-hardware/drivers/usbcon/usb-device-descriptors) | Identity fields, не radio commands |
| [USBPcap tour](https://desowin.org/usbpcap/tour.html) | Root hub capture и newly connected devices |
| [USBPcap source](https://github.com/desowin/usbpcap) | Owner implementation |
| [Microsoft WSL USB](https://learn.microsoft.com/en-us/windows/wsl/connect-usb) | USB ownership transfer |
| [usbipd WSL support](https://github.com/dorssel/usbipd-win/wiki/WSL-support) | Bind/attach/detach |
| [usbipd CLI source](https://raw.githubusercontent.com/dorssel/usbipd-win/master/Usbipd/Program.cs) | Viewed --wsl permits distribution name; check local help |

Permission нужен до обмена; detach начинает новый lifecycle.
force claim может отключить kernel driver.
Просмотренный Android CDC open не доказывает auto baud/DTR; отдельные методы посылают requests.
Windows pySerial имеет другие side effects. Проверяй actual implementation/version.
USB audio support не гарантирует радио passthrough на динамики.

Исторический [чат](https://chatgpt.com/c/6a75a86f-f658-83ed-83b3-f0a1eac23818) содержит user Windows output.
Старые assistant conclusions не приняты как доказательства.
Локальные raw observations: [CAPTURE_ANALYSIS.md](evidence/CAPTURE_ANALYSIS.md).
