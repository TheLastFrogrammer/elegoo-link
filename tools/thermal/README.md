# Thermal snapshots on a Raspberry Pi

`flir_snapshot.py` takes pictures from a FLIR Ax5 (A65 and family) over GigE Vision with Aravis, set up the way the
Android app sets the camera up (16-bit, temperature-linear, slowest frame rate). Each picture is saved twice: in the
app's iron palette with the centre's and the hottest point's temperature, and as the camera's raw 16-bit values.

Wire the PoE injector's data port to the Pi's Ethernet port (the camera on PoE out), keep the Pi on Wi-Fi, then:

```
sudo apt install -y aravis-tools-cli gir1.2-aravis-0.8 python3-gi python3-numpy python3-pil
sudo nmcli con add type ethernet ifname eth0 con-name flir ipv4.method link-local ipv6.method disabled
sudo nmcli con up flir
arv-tool-0.8                       # should list the camera at a 169.254 address
python3 flir_snapshot.py -n 3 --serve
```

`--serve` shares the pictures on port 8000: open the address it prints on a phone on the same Wi-Fi. The settings the
script changes on the camera last only until it restarts; nothing is saved to it.

## From a phone (Termux)

`flir_termux.py` is the Android app's camera code in plain Python (only Pillow is needed, for the pictures), for a phone
wired to the camera by Ethernet tethering, or on the same router:

```
pkg install python python-pillow
curl -fLO https://raw.githubusercontent.com/TheLastFrogrammer/elegoo-link/claude/dreamy-hypatia-4ru1wu/tools/thermal/flir_termux.py
python flir_termux.py find        # which cameras answer (reads only)
python flir_termux.py info        # the camera's settings (reads only)
python flir_termux.py snap -n 3   # three pictures into ./thermal, then: termux-open thermal/000.png
```

`snap` controls the camera only while it runs and hands it back as it found it; nothing is saved to the camera.
