# Owner-console discovery for the pinned stock CC2

Prerequisite: an already established owner shell on the printer, idle for the first test. This is a worksheet, not a remote-access installer. No commands here were run on a printer.

Check that `/tmp/elegoo_uds` exists as a Unix socket and that `/opt/bin/debug_window` exists. The stock launch script supplies that socket path with `-a`.

Run interactively:

```sh
debug_window /tmp/elegoo_uds
```

Enter one JSON object per line. The client appends the `0x03` framing byte; do not type a literal terminator into its terminal.

```json
{"id":1,"method":"list_endpoints","params":{}}
{"id":2,"method":"gcode/help","params":{}}
```

Confirm the actual running endpoints before trying subscriptions. A candidate output subscription, present in the inspected build, is:

```json
{"id":3,"method":"gcode/subscribe_report","params":{"response_template":{}}}
```

This records subsequent console output; it is not an export of old log files. The handler is named `handle_subscribe_output` despite the endpoint's `subscribe_report` name. Do not assume the upstream Klipper endpoint `gcode/subscribe_output` is registered.

The Unix socket also registers `gcode/script`; arbitrary scripts can move hardware or operate heaters. The first discovery worksheet deliberately submits none. Discover `objects/list` before selecting fields for `objects/query` or `objects/subscribe`.

If requests fail, retain the error, build fingerprint and bounded logs; do not kill/restart the printer process to obtain a console. The generic ADB configuration script in rootfs is not a verified printer USB-shell route. Camera USB/ADB operates on a different computer.

The bundled CLI can mishandle fragmented responses according to its published source. A production companion must buffer through each complete `0x03` frame, handle partial writes, match request IDs, bound buffers and keep one persistent connection.
