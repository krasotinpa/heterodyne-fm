2026-10-15T10:35:54.747812261Z | G6a start; repo main ab521c6; plan docs/plans/g6a-wsl-audio-plan.md
2026-10-15T10:36:10.180746547Z | apt-get install alsa-utils usbutils exit=0
2026-10-15T10:36:10.187014012Z | arecord: version 1.2.9 by Jaroslav Kysela <perex@perex.cz>; lsusb (usbutils) 017
2026-10-15T10:36:10.196535899Z | cdc_acm block file written exit=0
2026-10-15T10:36:10.235852680Z | modprobe cdc_acm (expect failure) exit=1
2026-10-15T10:36:10.240554822Z | lsmod cdc_acm: ''
2026-10-15T10:36:10.256966354Z | modprobe vhci_hcd exit=0
2026-10-15T10:36:10.267455246Z | modprobe usbmon exit=0
2026-10-15T10:36:10.315805109Z | modprobe snd-usb-audio exit=0
2026-10-15T10:36:10.322621531Z | lsmod: snd_usb_audio snd_hwdep snd_usbmidi_lib snd_pcm snd mc usbmon vhci_hcd usbip_core usbcore usb_common 
2026-10-15T10:36:10.325253484Z | usbmon dir: ls: cannot access '/sys/kernel/debug/usb/usbmon/': Permission denied 
2026-10-15T10:36:10.330173980Z | before attach: arecord -l: arecord: device_list:277: no soundcards found... ; cards: cat: /proc/asound/cards: No such file or directory 
2026-10-15T10:36:17.763860117Z | usbmon dir (sudo): 0s 0u 1s 1t 1u 2s 2t 2u 
2026-10-15T10:36:25.705977086Z | loggers started: dmesg -w --time-format iso, usbmon 0u
2026-10-15T10:40:24.348030283Z | owner: usbipd list -> 2-1 0483:5740 'DAB usb bridge, USB Serial Device (COM5)' Not shared; warning: USB filter 'USBPcap' incompatible, 'bind --force' required
2026-10-15T10:40:24.352643726Z | agent decision: use bind --force for BUSID 2-1 only (reason: USBPcap filter; alternative would be uninstalling USBPcap); restore with usbipd unbind
2026-10-15T10:42:23.342814272Z | owner: bind --force done, state 'Shared (forced)'; attach --wsl Ubuntu --busid 2-1 done (nat, host 172.28.96.1)
2026-10-15T10:42:28.410132065Z | lsusb -v exit=0
2026-10-15T10:42:28.423980459Z | lsmod: snd_usb_audio snd_hwdep snd_usbmidi_lib snd_pcm snd mc usbmon vhci_hcd usbip_core usbcore usb_common 
2026-10-15T10:42:28.427731388Z | ttyACM: ls: cannot access '/dev/ttyACM*': No such file or directory
2026-10-15T10:42:28.431184410Z | /dev/snd: timer 
2026-10-15T10:42:28.437759383Z | arecord -l: arecord: device_list:277: no soundcards found... 
2026-10-15T10:43:32.035677028Z | after attach: Bus001 Dev002 0483:5740; IF1 bound to snd-usb-audio, IF0/IF2/IF3 no driver; no sound card; dmesg silent; usbmon: SET_INTERFACE IF1 alt0/1/0, no UAC class requests; /proc/asound absent (CONFIG_SND_PROC_FS not set)
2026-10-15T10:43:32.080404084Z | dynamic debug snd_usb_audio +p exit=0
2026-10-15T10:43:32.081413480Z | deviation: re-probe via sysfs unbind/bind of usb device 1-1 instead of owner detach/attach
2026-10-15T10:43:33.114975394Z | sysfs rebind 1-1 exit=0
2026-10-15T10:43:49.938487316Z | debug: 'Found last interface = 0', '1:1: add audio endpoint 0x81', 'Creating new data endpoint #81', '1:1 Set sample rate 48000, clock 0'; then silent, no card
2026-10-15T10:43:50.215488921Z | unbind 1-1, modprobe -r snd-usb-audio exit=0
2026-10-15T10:43:50.255080287Z | modprobe snd-usb-audio ignore_ctl_error=1 exit=0
2026-10-15T10:43:50.302165867Z | bind 1-1 exit=0
2026-10-15T10:44:09.517078590Z | ignore_ctl_error=1: same debug lines, no card
2026-10-15T10:44:09.518409280Z | extra experiment beyond plan: skip_validation=1 + ignore_ctl_error=1 (host-side parsing only)
2026-10-15T10:44:09.836287531Z | modprobe skip_validation=1 ignore_ctl_error=1 exit=0
2026-10-15T10:44:40.980420965Z | kretprobes on snd_usb_create_mixer, snd_card_register, snd_media_device_create, snd_usb_add_audio_stream, snd_usb_parse_audio_interface, snd_usb_create_quirk
2026-10-15T10:44:42.027827987Z | sysfs rebind 1-1 with kretprobes
2026-10-15T10:46:08.384381604Z | kretprobe (ignore_ctl_error=1 skip_validation=1): snd_usb_parse_audio_interface=0, snd_usb_add_audio_stream=0, snd_usb_create_mixer=-19, snd_card_register=0; function_graph: card registered then snd_card_free -> ftrace-graph.txt
2026-10-15T10:46:08.684864967Z | reload snd-usb-audio with defaults exit=0
2026-10-15T10:47:30.693259824Z | defaults: same pattern (snd_usb_create_mixer -> snd_card_register -> snd_card_free); v6.18 card.c: mixer error -> __error -> try_to_register_card -> snd_card_free, probe returns error silently
2026-10-15T10:47:30.694294196Z | proposed extra step (libusb iso capture IF1 alt1) not executed: permission denied by session classifier; asking owner
2026-10-15T10:49:20.102770407Z | owner: 'разрешаю захват libusb'
2026-10-15T10:49:22.351473381Z | apt-get install libusb-1.0-0-dev gcc exit=0
2026-10-15T10:50:03.587744417Z | iso_capture.c built (research-tools/g6a-iso), sha256 7e78435416f04f2b
2026-10-15T10:50:03.867941446Z | modprobe -r snd-usb-audio exit=0; IF1 driver now: ''
2026-10-15T10:50:03.869841793Z | start iso_capture 5 s
2026-10-15T10:50:08.036032196Z | iso_capture 5 s exit=0: transfer status 3;transfer status 3;transfer status 3;transfer status 3;transfer status 3;transfer status 3;transfer status 3;packets 4496, bad 0, bytes 863424;len 192: 4464;len 198: 32;
2026-10-15T10:50:20.184093072Z | start iso_capture 30 s
2026-10-15T10:50:50.021721485Z | iso_capture 30 s exit=0: packets 29312, bad 0, bytes 5628288;len 192: 29248;len 198: 64;
2026-10-15T10:51:38.953335623Z | stats: rms -20.6 dBFS, peak -5.7 dBFS, zeros 0.028%, no zero runs >50 ms; content mono duplicated, stream offset by 2 bytes; aligned WAV -> .scratch/g6a.wav for owner
2026-10-15T10:54:02.714902893Z | owner listened (verbatim): '1. слышно только шипение, рчи или музыки нет 2. тресков, прерываний, или щелчков нет'
2026-10-15T11:00:52.689820949Z | owner: Вариант А, station 90.3 FM Авторадио; 'разрешаю тебе, делай' (whitelisted rai/band/tuneup/tunedown via libusb IF3)
2026-10-15T11:01:15.805858166Z | cdc_send.c built, sha256 ecc64b669912ddba; guards tested offline (seek rejected, rai too soon rejected)
2026-10-15T11:01:15.806856329Z | pass 1: rai, band, rai
2026-10-15T11:01:43.097249665Z | pass 1 exit=0
2026-10-15T11:01:54.488771811Z | pass 1 result: AM cid58 1044 -> after BAND FM cid30 90.5 MHz
2026-10-15T11:01:54.489848529Z | pass 2: tuneup, tuneup, rai
2026-10-15T11:02:13.031736859Z | pass 2 exit=0
2026-10-15T11:02:25.773429178Z | pass 2 result: FM cid29 90.4 -> cid28 90.3 MHz; requestAllInfo confirms cid28 90.3; FM 0x24 +7 = 4
2026-10-15T11:02:25.775146222Z | start iso_capture 30 s on FM 90.3
2026-10-15T11:02:55.036660660Z | iso_capture FM exit=0: packets 28680, bad 0, bytes 5506944;len 192: 28616;len 198: 64;
2026-10-15T11:05:49.635321384Z | owner listened FM 90.3 (verbatim): '1. Слышна речь, музыки нет в фрагменте. 2. Звук чистый, без щелчков, треска или прерываний'
2026-10-15T11:08:11.259441627Z | owner: usbipd detach --busid 2-1; usbipd list shows 2-1 'Not shared' (binding reverted)
2026-10-15T11:08:13.274248057Z | after detach: lsusb 0483:5740 ''
2026-10-15T11:08:26.604824892Z | usbmon logger stopped (pkill matched own shell; usbmon cat ended)
2026-10-15T11:08:27.636602938Z | dmesg logger stopped (kill 17138) remaining: ''
2026-10-15T11:08:28.024615642Z | kprobes/ftrace reset
2026-10-15T11:08:28.048736769Z | dynamic debug off
2026-10-15T11:08:28.062102326Z | removed g6a-block-cdc-acm.conf exit=0
2026-10-15T11:08:56.562702296Z | usbmon audit: standard requests only (SET_ADDRESS, SET_CONFIGURATION, GET_STATUS, GET_DESCRIPTOR, SET_INTERFACE IF1 alt0/1); class 0; vendor 0; bulk OUT 6, all whitelisted -> usbmon-audit.txt
