#!/usr/bin/env python3
"""Watch the printer through Link Workshop's local API: pair once, then print status changes and save a camera picture.

    python3 watch.py http://192.168.1.20:8765            # pairs (approve on the phone), then watches
    python3 watch.py http://192.168.1.20:8765 --picture  # also saves the camera's current picture as printer.jpg

Only the standard library. The token is kept in ~/.linkworkshop-token; delete it (and remove the device on the phone) to unpair.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

TOKEN_FILE = os.path.expanduser("~/.linkworkshop-token")


def call(base, path, token=None, method="GET", body=None):
    request = urllib.request.Request(base + path, method=method, data=None if body is None else json.dumps(body).encode())
    if token:
        request.add_header("Authorization", "Bearer " + token)
    if body is not None:
        request.add_header("Content-Type", "application/json")
    return urllib.request.urlopen(request, timeout=30)


def pair(base):
    asked = json.load(call(base, "/api/v1/pair", method="POST", body={"name": "watch.py on " + os.uname().nodename}))
    print("Approve on the phone (Link Workshop > Settings > Other apps). Code:", asked["code"])
    until = time.time() + asked["expires_in"]
    while time.time() < until:
        try:
            state = json.load(call(base, asked["poll"]))
        except urllib.error.HTTPError as e:
            if e.code == 404:
                break  # the request ran out on the phone
            raise
        if state["state"] == "approved":
            return state["token"]
        if state["state"] == "denied":
            sys.exit("The phone refused.")
        time.sleep(2)
    sys.exit("Not approved in time.")


def events(base, token):
    """Server-Sent Events: yields (event, data) pairs; the server sends a comment line every 15 s to keep it open."""
    response = call(base, "/api/v1/events", token)
    event, data = None, []
    for raw in response:
        line = raw.decode("utf-8").rstrip("\n")
        if line.startswith("event: "):
            event = line[7:]
        elif line.startswith("data: "):
            data.append(line[6:])
        elif line == "" and event:
            yield event, json.loads("\n".join(data))
            event, data = None, []


def main():
    base = sys.argv[1].rstrip("/")
    token = open(TOKEN_FILE).read().strip() if os.path.exists(TOKEN_FILE) else None
    if not token:
        token = pair(base)
        with open(TOKEN_FILE, "w") as f:
            f.write(token)
        os.chmod(TOKEN_FILE, 0o600)
    try:
        if "--picture" in sys.argv:
            with open("printer.jpg", "wb") as f:
                f.write(call(base, "/api/v1/cameras/printer/snapshot.jpg", token).read())
            print("Saved printer.jpg")
        watch(base, token)
    except urllib.error.HTTPError as e:
        if e.code != 401:
            raise
        os.remove(TOKEN_FILE)  # removed on the phone: the next run pairs again
        sys.exit("This device was removed on the phone. Run again to pair it again.")


def watch(base, token):
    for event, data in events(base, token):
        if event == "status":
            state, job = data.get("state", {}), data.get("print", {})
            print(f"{data['updated_at']}  {state.get('words', '?'):<24} {job.get('progress', ''):>3}%  "
                  f"layer {job.get('layer', '-')}/{job.get('total_layers', '-')}  {job.get('file', '')}")
        else:
            print(f"{data['at']}  EVENT {data['type']}: {data['words']}")


if __name__ == "__main__":
    main()
