#!/usr/bin/env python3
"""THROWAWAY PROTOTYPE server. No persistence or product behaviour."""

from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from pathlib import Path
import os

ROOT = Path(__file__).resolve().parent
BIND = os.environ.get("PROTOTYPE_BIND", "0.0.0.0")
PORT = int(os.environ.get("PROTOTYPE_PORT", "4173"))

os.chdir(ROOT)
print(f"Imported Mission administration prototype listening on {BIND}:{PORT}")
ThreadingHTTPServer((BIND, PORT), SimpleHTTPRequestHandler).serve_forever()
