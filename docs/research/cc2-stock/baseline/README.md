# CC2 stock-firmware investigation

Read STOCK-FIRMWARE-INVESTIGATION.md for findings and STOCK-FIRMWARE-HANDOFF.md for the next work. stock-firmware.html is the readable self-contained report.

evidence/ contains the verified package chain, selected component hashes, recovered API map, targeted direct-call cross-references, original-ARM admission test and tool versions. These are offline results for one fingerprinted stock image. They do not establish console access or coexistence on the owner's device.

tools/collect-stock-facts.sh is the only on-printer worksheet. It requires an already-established owner-controlled shell, reads non-credential system/path metadata, starts no services and changes no mode or configuration. Syntax was checked; it has not run on a CC2 in this investigation. It is not an access/installation tool.

The Python tools reproduce offline inspection. They do not contain printer network clients, flashing commands, a modified firmware or an installer. No vendor image/executable, private account data, signing key or firmware AES key is bundled.

## Offline reproduction

Use a host with Python3, cryptography and c++filt. Run from tools/ and keep generated/third-party material there. Install the analysis dependencies as recorded in evidence/TOOL-VERSIONS.json into tools/tooling/ (pip --target tooling). Dependencies are not bundled.

Obtain the following external inputs at the exact relative paths below:

- official-02.01.01.07.zip.sig: original CC2 update at https://public.suchmeme.nl/cc2-02.01.01.07-1cbee81a985601d1944cea50773793ba-release-abroad.zip.sig . Required SHA256 278a0606a072ae432e9bf2d4f0157acba4570d1c85dbc768c359fef53ac5f406.
- stock-public.pem: https://raw.githubusercontent.com/Neme77/centauri-carbon-2-community/706807122bdc06cd5dc26b0714a6bd66576f6692/builder/current/keys/cc2_stock_public.pem . Public verification key only.
- opencentauri/docs/extras/cc2_update_decrypt.html: https://raw.githubusercontent.com/OpenCentauri/OpenCentauri/c59a3febfcadccfa27dc53f9342e019c29a20344/docs/extras/cc2_update_decrypt.html . The tool reads the published research key from this source rather than bundling it. Key hash must match the documented baseline.

Run, sequentially:

~~~text
python inspect_stock.py
python index_stock.py
python analyze_elf.py
python extract_api_map.py
python test_download_gate.py
~~~

The tools read the supplied package and create stock-unpacked/ analysis output. Selected extracted binaries are stored without execute permission. The guard test runs a small ARM instruction block under Unicorn with explicitly mocked callees; it does not boot or run the firmware as a system. inspect_stock, index_stock, extract_api_map and the gate proof reject unexpected input hashes. analyze_elf is an offline disassembler and must use the verified indexed output.

Expected results: 3 successful ELEG signature/hash layers; exact stock component fingerprints; 55 unique protocol registrations; 16 gate cases with idle state1 admitted to further checks and all other tested states rejected. No download success, daemon operation, physical-print behavior or firmware installation is claimed.

The supplied TARGET-XREFS.json is a bounded direct ARM BL-call scan, not a complete dynamic/indirect-call graph. Its callers do not prove an undocumented path can never exist. The static inspection and emulation are separate from device qualification.

Hosted study: https://cc2-workshop-concepts.bagels.chatgpt.site/stock-firmware.html

## Deliverable verification

The report has 10 unique section anchors and 37 checked link occurrences (internal anchors/local targets and external URL form; external-page availability is not asserted). Its 16-entry ZIP passes CRC checks. Five Python tools parse, the console worksheet passes sh -n, and the extracted-file index was reproduced with eight selected stock files and265,907 named/nonzero symbols. All16 admission cases and the55-unique-method map passed their checks. No browser pixel QA was available.
