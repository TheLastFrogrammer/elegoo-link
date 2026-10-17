# CC2 stock access follow-up

This package extends the original Stock Firmware Investigation with the complete API map, stock console quickstart, cloud-download/database traces, media paths and camera findings. Read STOCK-ACCESS-FOLLOWUP.md first. CONSOLE-QUICKSTART.md requires an already established owner shell; it does not create one.

No vendor firmware binaries, camera dumps, credentials, signed download URLs or executable printer patches are included. The annotated disassembly is analysis output from the exact binaries identified by the report. This work was performed offline and has not been qualified on a printer.

The evidence folders separate the printer executable from the camera-host executable; their address spaces are different. Each JSON includes the function address, size, direct call targets and decoded string references. Direct calls do not constitute a complete indirect call graph. Literal references do not establish that a particular log level is enabled at runtime.

To reproduce, obtain the exact stock package and published public key described in the original bundle, verify/unpack with its inspect_stock.py, install its listed Python dependencies and run index_stock.py. Then run tools/index_camera.py and tools/trace_stock.py from the original research directory, with the scripts placed alongside its tooling/ and stock-unpacked/ directories. The camera indexer extracts only the named regular ELF and refuses unexpected hashes. No extracted binaries are executed.

Examples:

```sh
python trace_stock.py 0x81c688 0x81b394 0x90ff44 0x91aa3c 0x91a8dc
python trace_stock.py --camera 0x146990 0x14624c 0x146418 0x13c668
```

For the product direction, implement exact-input retention in Link Workshop first. Keep the manually bootstrapped file reader and read-only UDS probe as separate, qualified experiments. Reboot persistence and official-update compatibility remain unverified.
