2026-10-14T18:12:20.6834361Z | Решение владельца 14.10.2026: одна отправка requestAllInfo, command-catalog.md
2026-10-14T18:12:20.6990733Z | session created: \\wsl.localhost\Ubuntu\home\user\sources\super-radio-android\raw\g4\20261014T181220Z; user confirmed antenna connected, Module not connected
2026-10-14T18:12:20.8650738Z | keep-awake helper PID=1292 (SetThreadExecutionState, 900 s)
2026-10-14T18:12:22.4863760Z | step2 environment -> 00-environment.txt; IsAdministrator=False
2026-10-14T18:12:22.5438429Z | step3 interfaces: \\.\USBPcap1, \\.\USBPcap2, \\.\USBPcap3
2026-10-14T18:12:30.9778563Z | step4 precheck \\.\USBPcap1 exists=True size=292
2026-10-14T18:12:38.9474962Z | step4 precheck \\.\USBPcap2 exists=True size=2644
2026-10-14T18:12:46.9768935Z | step4 precheck \\.\USBPcap3 exists=True size=292
2026-10-14T18:12:48.4151658Z | step1 pnp-before -> pnp-before.txt
2026-10-14T18:12:48.4658694Z | step2 tshark started PID=14944 duration=480
2026-10-14T18:13:18.5657493Z | step3 pcapng grew=False size=2852 (growth not required, plan item 8)
2026-10-14T18:13:54.0472170Z | step4 user confirmed Module connected; elapsed since tshark start = 66 s
2026-10-14T18:14:15.0131804Z | step5 pnp-after devices=3 -> pnp-after.txt
2026-10-14T18:14:15.0288101Z | step5 port=COM5 (FriendlyName: USB Serial Device (COM5))
2026-10-14T18:14:54.7396325Z | step6 user confirmed observing Module
2026-10-14T18:14:54.8897208Z | step7 g4.ps1 started PID=25240 port=COM5
2026-10-14T18:17:57.1449606Z | step7 g4.ps1 finished=True exit=0 (STOP condition if not finished)
2026-10-14T18:18:49.9494475Z | step8 pnp-after-tx devices=3 -> pnp-after-tx.txt
2026-10-14T18:18:49.9594069Z | step9 user answers (verbatim, two messages): "Звуков не было" ; "нагрев слабый"
2026-10-14T18:18:50.0894683Z | step8 pnp-after == pnp-after-tx: True
2026-10-14T18:20:56.0073289Z | step10 tshark exited
2026-10-14T18:21:39.2142853Z | step9 user answer (verbatim, third message): "на модуле индикаторов нет, индикаторый компьютера поведение не меняли"
2026-10-14T18:21:39.2339885Z | step11 user confirmed Module disconnected
2026-10-14T18:21:50.6461300Z | step11 pnp-detached -> pnp-detached.txt
2026-10-14T18:21:50.6778262Z | step12 copy g4-reads.csv src=5710 dst=5710
2026-10-14T18:21:50.6935025Z | step12 copy g4-rx.bin src=4542 dst=4542
2026-10-14T18:21:50.7018752Z | step12 copy g4-sent.flag src=0 dst=0
2026-10-14T18:21:50.7158867Z | step12 copy g4.ps1 src=2713 dst=2713
2026-10-14T18:21:50.7306920Z | step12 copy tshark.err src=49 dst=49
2026-10-14T18:21:50.7356851Z | step12 copy tshark.out src=0 dst=0
2026-10-14T18:21:50.7476925Z | step12 copy usb.pcapng src=29440 dst=29440
2026-10-14T18:21:51.1114650Z | step13 tshark -Y usb.idVendor==0x0483 lines=1
2026-10-14T18:22:08.9900943Z | summary: written with the Write tool (UTF-8 no BOM, LF)
